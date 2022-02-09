package com.squareup.anvil.compiler.internal.reference

import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.internal.annotationOrNull
import com.squareup.anvil.compiler.internal.argumentType
import com.squareup.anvil.compiler.internal.asClassName
import com.squareup.anvil.compiler.internal.classDescriptor
import com.squareup.anvil.compiler.internal.contributesBindingFqName
import com.squareup.anvil.compiler.internal.contributesMultibindingFqName
import com.squareup.anvil.compiler.internal.contributesSubcomponentFqName
import com.squareup.anvil.compiler.internal.contributesToFqName
import com.squareup.anvil.compiler.internal.directSuperClassAndInterfaces
import com.squareup.anvil.compiler.internal.findAnnotation
import com.squareup.anvil.compiler.internal.findAnnotationArgument
import com.squareup.anvil.compiler.internal.functions
import com.squareup.anvil.compiler.internal.getAnnotationValue
import com.squareup.anvil.compiler.internal.isDaggerScope
import com.squareup.anvil.compiler.internal.isInterface
import com.squareup.anvil.compiler.internal.isQualifier
import com.squareup.anvil.compiler.internal.reference.ClassReference.Descriptor
import com.squareup.anvil.compiler.internal.reference.ClassReference.Psi
import com.squareup.anvil.compiler.internal.requireFqName
import com.squareup.anvil.compiler.internal.scope
import com.squareup.anvil.compiler.internal.scopeOrNull
import com.squareup.anvil.compiler.internal.toAnnotationSpec
import com.squareup.anvil.compiler.internal.toFqNames
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality.ABSTRACT
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import kotlin.LazyThreadSafetyMode.NONE

/**
 * Used to create a common type between [KtClassOrObject] class references and [ClassDescriptor]
 * references, to streamline parsing.
 *
 * @see toClassReference
 */
@ExperimentalAnvilApi
public sealed class ClassReference {

  public abstract val classId: ClassId
  public abstract val fqName: FqName
  public abstract val module: AnvilModuleDescriptor

  public abstract val functions: List<FunctionReference>
  public abstract val annotations: List<AnnotationReference>

  override fun toString(): String {
    return "${this::class.qualifiedName}($fqName)"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ClassReference) return false

    if (fqName != other.fqName) return false

    return true
  }

  override fun hashCode(): Int {
    return fqName.hashCode()
  }

  public class Psi(
    public val clazz: KtClassOrObject,
    override val classId: ClassId,
    override val module: AnvilModuleDescriptor
  ) : ClassReference() {
    override val fqName: FqName = classId.asSingleFqName()

    override val functions: List<FunctionReference.Psi> by lazy(NONE) {
      clazz
        .functions(includeCompanionObjects = false)
        .map { it.toFunctionReference(this) }
    }

    override val annotations: List<AnnotationReference.Psi> by lazy(NONE) {
      clazz.annotationEntries.map { it.toAnnotationReference(module) }
    }
  }

  public class Descriptor(
    public val clazz: ClassDescriptor,
    override val classId: ClassId,
    override val module: AnvilModuleDescriptor
  ) : ClassReference() {
    override val fqName: FqName = classId.asSingleFqName()

    override val functions: List<FunctionReference> by lazy(NONE) {
      clazz.unsubstitutedMemberScope
        .getContributedDescriptors(kindFilter = DescriptorKindFilter.FUNCTIONS)
        .filterIsInstance<FunctionDescriptor>()
        .map { it.toFunctionReference(this) }
    }

    override val annotations: List<AnnotationReference> by lazy(NONE) {
      clazz.annotations.map { it.toAnnotationReference(module) }
    }
  }
}

@ExperimentalAnvilApi
public fun ClassDescriptor.toClassReference(module: ModuleDescriptor): Descriptor =
  module.asAnvilModuleDescriptor().getClassReference(this)

@ExperimentalAnvilApi
public fun KtClassOrObject.toClassReference(module: ModuleDescriptor): Psi =
  module.asAnvilModuleDescriptor().getClassReference(this)

/**
 * Attempts to resolve the [FqName] to a [ClassDescriptor] first, then falls back to a
 * [KtClassOrObject] if the descriptor resolution fails. This will happen if the code being parsed
 * was generated as part of the compilation round for this module.
 */
@ExperimentalAnvilApi
public fun FqName.toClassReferenceOrNull(module: ModuleDescriptor): ClassReference? =
  module.asAnvilModuleDescriptor().getClassReferenceOrNull(this)

@ExperimentalAnvilApi
public fun FqName.toClassReference(module: ModuleDescriptor): ClassReference {
  return toClassReferenceOrNull(module)
    ?: throw AnvilCompilationException("Couldn't resolve ClassReference for $this.")
}

/**
 * This will return all super types as [ClassReference], whether they're parsed as [KtClassOrObject]
 * or [ClassDescriptor]. This will include generated code, assuming it has already been generated.
 * The returned sequence will be distinct by FqName, and Psi types are preferred over Descriptors.
 *
 * The first elements in the returned sequence represent the direct superclass to the receiver. The
 * last elements represent the types which are furthest up-stream.
 *
 * @param includeSelf If true, the receiver class is the first element of the sequence
 */
@ExperimentalAnvilApi
public fun ClassReference.allSuperTypeClassReferences(
  includeSelf: Boolean = false
): Sequence<ClassReference> {
  return generateSequence(sequenceOf(this)) { superTypes ->
    superTypes
      .map { classRef -> classRef.directSuperClassReferences() }
      .flatten()
      .takeIf { it.firstOrNull() != null }
  }
    .drop(if (includeSelf) 0 else 1)
    .flatten()
    .distinctBy { it.fqName }
}

@ExperimentalAnvilApi
public fun ClassReference.directSuperClassReferences(): Sequence<ClassReference> {
  return when (this) {
    is Descriptor -> clazz.directSuperClassAndInterfaces()
      .asSequence()
      .map { it.fqNameSafe.toClassReference(module) }
    is Psi -> if (clazz is KtEnumEntry) {
      emptySequence()
    } else {
      clazz.superTypeListEntries
        .asSequence()
        .map { it.requireFqName(module).toClassReference(module) }
    }
  }
}

@ExperimentalAnvilApi
public fun ClassReference.innerClasses(): Sequence<ClassReference> {
  return when (this) {
    is Descriptor ->
      clazz.unsubstitutedMemberScope
        .getContributedDescriptors(kindFilter = DescriptorKindFilter.CLASSIFIERS)
        .asSequence()
        .filterIsInstance<ClassDescriptor>()
        .map { it.toClassReference(module) }
    is Psi ->
      generateSequence(clazz.declarations.filterIsInstance<KtClassOrObject>()) { classes ->
        classes
          .flatMap { it.declarations }
          .filterIsInstance<KtClassOrObject>()
          .ifEmpty { null }
      }.flatten().map { it.toClassReference(module) }
  }
}

/**
 * @param parameterName The name of the parameter to be found, not including any variance modifiers.
 * @return The 0-based index of a declared generic type.
 */
@ExperimentalAnvilApi
public fun ClassReference.indexOfTypeParameter(parameterName: String): Int {
  return when (this) {
    is Descriptor ->
      clazz.declaredTypeParameters
        .indexOfFirst { it.name.asString() == parameterName }
    is Psi ->
      clazz.typeParameters
        .indexOfFirst { it.identifyingElement?.text == parameterName }
  }
}

@ExperimentalAnvilApi
public fun ClassReference.scopeOrNull(
  annotationFqName: FqName
): FqName? {
  return when (this) {
    is Descriptor -> clazz.annotationOrNull(annotationFqName)
      ?.scope(module)
      ?.fqNameSafe
    is Psi -> clazz.scopeOrNull(annotationFqName, module)
  }
}

private fun replacesIndex(annotationFqName: FqName): Int {
  return when (annotationFqName) {
    contributesToFqName -> 1
    contributesBindingFqName, contributesMultibindingFqName -> 2
    contributesSubcomponentFqName -> 4
    else -> throw NotImplementedError(
      "Couldn't find index of replaces argument for $annotationFqName."
    )
  }
}

@ExperimentalAnvilApi
public fun ClassReference.replaces(
  annotationFqName: FqName,
  index: Int = replacesIndex(annotationFqName)
): List<ClassReference> =
  when (this) {
    is Descriptor -> {
      val replacesValue = clazz.annotationOrNull(annotationFqName)
        ?.getAnnotationValue("replaces") as? ArrayValue

      replacesValue
        ?.value
        ?.map { it.argumentType(module).classDescriptor().toClassReference(module) }
    }
    is Psi ->
      clazz
        .findAnnotation(annotationFqName, module)
        ?.findAnnotationArgument<KtCollectionLiteralExpression>(name = "replaces", index = index)
        ?.toFqNames(module)
        ?.map { it.toClassReference(module) }
  }.orEmpty()

@ExperimentalAnvilApi
public fun ClassReference.qualifiers(): List<AnnotationSpec> {
  return when (this) {
    is Descriptor -> clazz.annotations.filter { it.isQualifier() }
      .map { it.toAnnotationSpec(module) }
    is Psi -> clazz.annotationEntries.filter { it.isQualifier(module) }
      .map { it.toAnnotationSpec(module) }
  }
}

@ExperimentalAnvilApi
public fun ClassReference.daggerScopes(): List<AnnotationSpec> = when (this) {
  is Descriptor -> clazz.annotations.filter { it.isDaggerScope() }
    .map { it.toAnnotationSpec(module) }
  is Psi -> clazz.annotationEntries.filter { it.isDaggerScope(module) }
    .map { it.toAnnotationSpec(module) }
}

@ExperimentalAnvilApi
public fun ClassReference.asClassName(): ClassName = classId.asClassName()

@ExperimentalAnvilApi
public fun ClassReference.isInterface(): Boolean = when (this) {
  is Descriptor -> clazz.kind == ClassKind.INTERFACE
  is Psi -> clazz.isInterface()
}

@ExperimentalAnvilApi
public fun ClassReference.isAbstractClass(): Boolean = when (this) {
  is Descriptor -> clazz.modality == ABSTRACT
  is Psi -> clazz.hasModifier(ABSTRACT_KEYWORD)
}

@ExperimentalAnvilApi
@Suppress("FunctionName")
public fun AnvilCompilationExceptionClassReference(
  classReference: ClassReference,
  message: String,
  cause: Throwable? = null
): AnvilCompilationException = when (classReference) {
  is Psi -> AnvilCompilationException(
    element = classReference.clazz,
    message = message,
    cause = cause
  )
  is Descriptor -> AnvilCompilationException(
    classDescriptor = classReference.clazz,
    message = message,
    cause = cause
  )
}