package com.jetbrains.rd.generator.nova.cpp

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.Enum
import com.jetbrains.rd.generator.nova.FlowKind.*
import com.jetbrains.rd.generator.nova.cpp.Signature.Constructor
import com.jetbrains.rd.generator.nova.cpp.Signature.MemberFunction
import com.jetbrains.rd.generator.nova.util.joinToOptString
import com.jetbrains.rd.util.eol
import com.jetbrains.rd.util.hash.IncrementalHash64
import com.jetbrains.rd.util.string.Eol
import com.jetbrains.rd.util.string.PrettyPrinter
import com.jetbrains.rd.util.string.condstr
import java.io.File


private fun StringBuilder.appendDefaultInitialize(member: Member, typeName: String) {
    if (member is Member.Field && (member.isOptional || member.defaultValue != null)) {
        append("{")
        val defaultValue = member.defaultValue
        when (defaultValue) {
            is String -> append(if (member.type is Enum) "$typeName::$defaultValue" else """L"$defaultValue"""")
            is Long, is Boolean -> append(defaultValue)
//            else -> if (member.isOptional) append("tl::nullopt")
        }
        append("}")
    }
}

/*please set VsWarningsDefault to null if you don't need disabling VS warnings
val VsWarningsDefault : IntArray? = null*/
val VsWarningsDefault: IntArray? = intArrayOf(4250, 4307)

open class Cpp17Generator(override val flowTransform: FlowTransform, val defaultNamespace: String, override val folder: File) : GeneratorBase() {

    //region language specific properties
    object Namespace : ISetting<String, Declaration>

    val Declaration.namespace: String
        get() {
            val ns = getSetting(Namespace) ?: defaultNamespace
            return ns.split('.').joinToString(separator = "::")
        }

    val nestedNamespaces = defaultNamespace.split('.')

    object PublicCtors : ISetting<Unit, Declaration>

    object FsPath : ISetting<(Cpp17Generator) -> File, Toplevel>

    object TargetName : ISetting<String, Toplevel>

    private fun Declaration.fsName(isDefinition: Boolean) =
            "$name.${if (isDefinition) "cpp" else "h"}"

    protected open fun Toplevel.fsPath(): File = getSetting(FsPath)?.invoke(this@Cpp17Generator)
            ?: File(folder, this.name)


    protected open fun Declaration.fsPath(tl: Toplevel, isDefinition: Boolean): File = getSetting(FsPath)?.invoke(this@Cpp17Generator)
            ?: File(tl.fsPath(), fsName(isDefinition))

    private fun Root.targetName(): String {
        return getSetting(TargetName) ?: this.name
    }

    private val Class.isInternRoot: Boolean
        get() = internRootForScopes.isNotEmpty()

    private fun InternScope.hash(): String {
        val s = this.keyName
        return """rd::util::getPlatformIndependentHash("$s")"""
    }

    private fun Class.withInternRootsHere(field: String): String {
        val roots = internRootForScopes/*.map { """rd::util::getPlatformIndependentHash("$it")""" }*/.joinToString { """"$it"""" }
        return "ctx.withInternRootsHere($field, {$roots})"
    }
    //endregion

    //region language specific types
    private open class RdCppLibraryType(override val name: String) : IType

    private object RdId : RdCppLibraryType("rd::RdId")

    private object ByteArray : RdCppLibraryType("rd::Buffer::ByteArray")

    private object IPolymorphicSerializable : RdCppLibraryType("rd::IPolymorphicSerializable")

    private object IUnknownInstance : RdCppLibraryType("rd::IUnknownInstance")

    private object RdBindableBase : RdCppLibraryType("rd::RdBindableBase")

    private object RdExtBase : RdCppLibraryType("rd::RdExtBase")

    //endregion
    fun String.isWrapper(): Boolean {
        return startsWith("rd::Wrapper")
    }

    private fun String.wrapper(): String {
        return if (isWrapper()) {
            this
        } else {
            "rd::Wrapper<$this>"
        }
    }

    protected fun String.optional(): String {
        return "tl::optional<$this>"
    }

    //endregion

    //region PrettyPrinter

    private fun String.include(extension: String = "h"): String {
        return """#include "${this}.$extension""""
    }

    private fun Declaration.include(): String {
        return this.name.include()
    }

    private fun IType.include(): String {
        return this.name.include()
    }

    private fun PrettyPrinter.carry(): String {
        return ";" + eolKind.value
    }

    protected fun PrettyPrinter.block(prefix: String, postfix: String, body: PrettyPrinter.() -> Unit) {
        +prefix
        indent(body)
        +postfix
    }

    protected fun PrettyPrinter.braceBlock(body: PrettyPrinter.() -> Unit) {
        +"{"
        indent(body)
        +"}"
    }

    protected fun PrettyPrinter.titledBlock(title: String, body: PrettyPrinter.() -> Unit) {
        +"$title {"
        indent(body)
        +"};"
    }

    protected fun PrettyPrinter.comment(comment: String) {
        +"${eolKind.value}//$comment"
    }

    protected fun PrettyPrinter.declare(signature: Signature?) {
        signature?.let {
            this.println(it.decl())
        }
    }

    protected fun PrettyPrinter.declare(signatures: List<Signature>) {
        signatures.forEach {
            declare(it)
        }
    }

    protected fun PrettyPrinter.define(signature: Signature?, body: PrettyPrinter.() -> Unit) {
        signature?.let {
            this.println(it.def())
            braceBlock {
                this.body()
            }
        }
    }

    private fun PrettyPrinter.private() {
        +"private:"
    }

    private fun PrettyPrinter.public() {
        +"public:"
    }

    private fun Member.getter() = "get_${this.publicName}"

    private fun PrettyPrinter.surroundWithNamespaces(body: PrettyPrinter.() -> Unit) {
        nestedNamespaces.foldRight(body) { s, acc ->
            {
                titledBlock("namespace $s") {
                    acc()
                }
            }
        }()
        //don't touch. it works
    }
    //endregion

    //region IType.
    fun IType.isPrimitive() = this in PredefinedFloating || this in PredefinedIntegrals

    fun IType.isAbstract0() = (this is Struct.Abstract || this is Class.Abstract)
    fun IType.isAbstract() = (this.isAbstract0()) || (this is InternedScalar && (this.itemType.isAbstract0()))

    fun Member.Field.isAbstract() = this.type.isAbstract()

    fun IType.substitutedName(scope: Declaration, rawType: Boolean = false, omitNullability: Boolean = false): String = when (this) {
        is Enum -> name
//        is Struct.Concrete -> sanitizedName(scope)
        is Declaration -> {
            val fullName = sanitizedName(scope)
            if (rawType) {
                fullName
            } else {
                fullName.wrapper()
            }
        }
        is INullable -> {
            if (omitNullability) {
                itemType.substitutedName(scope, rawType)
            } else {
                when (itemType) {
                    is PredefinedType.string -> itemType.substitutedName(scope, true).wrapper()
                    is PredefinedType -> itemType.substitutedName(scope, true).optional()
                    is Enum -> itemType.substitutedName(scope, true).optional()
                    else -> itemType.substitutedName(scope, true).wrapper()
                }
            }
        }
        is InternedScalar -> {
            if (rawType) {
                itemType.substitutedName(scope, rawType)
            } else {
                itemType.substitutedName(scope, rawType).wrapper()
            }
        }
        is IArray -> "std::vector<${itemType.substitutedName(scope, false)}>"
        is IImmutableList -> "std::vector<${itemType.substitutedName(scope, false)}>"

        is PredefinedType.byte -> "signed char"
        is PredefinedType.char -> "wchar_t"
        is PredefinedType.int -> "int32_t"
        is PredefinedType.long -> "int64_t"
        is PredefinedType.string -> {
            val type = "std::wstring"
            if (rawType) {
                type
            } else {
                type.wrapper()
            }

        }
        is PredefinedType.dateTime -> "Date"
        is PredefinedType.guid -> "UUID"
        is PredefinedType.uri -> "URI"
        is PredefinedType.secureString -> "RdSecureString"
        is PredefinedType.void -> "rd::Void"
        is PredefinedType -> name.decapitalize()
        is RdCppLibraryType -> name

        else -> fail("Unsupported type ${javaClass.simpleName}")
    }

    fun IType.templateName(scope: Declaration, omitNullability: Boolean = false) = substitutedName(scope, true, omitNullability)

    protected val IType.isPrimitivesArray
        get() = (this is IArray || this is IImmutableList) && this.isPrimitive()

    protected fun IType.leafSerializerRef(scope: Declaration): String? {
        return when (this) {
            is Enum -> "Polymorphic<${sanitizedName(scope)}>"
            is PredefinedType -> "Polymorphic<${templateName(scope)}>"
            is Declaration ->
                if (isAbstract) {
                    "AbstractPolymorphic<${sanitizedName(scope)}>"
                } else {
                    "Polymorphic<${sanitizedName(scope)}>"
                }


            is IArray -> if (this.isPrimitivesArray) "Polymorphic<${substitutedName(scope)}>" else null
            else -> null
        }?.let { "rd::$it" }
    }

    protected fun IType.serializerRef(scope: Declaration, isUsage: Boolean): String {
        return leafSerializerRef(scope)
                ?: isUsage.condstr { "${scope.name}::" } + when (this) {
                    is InternedScalar -> "__${name}At${internKey.keyName}Serializer"
                    else -> "__${name}Serializer"
                }
    }

//endregion

    //region Member.
    val Member.Reactive.actualFlow: FlowKind get() = flowTransform.transform(flow)

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    protected open val Member.Reactive.implSimpleName: String
        get () = "rd::" + when (this) {
            is Member.Reactive.Task -> when (actualFlow) {
                Sink -> "RdEndpoint"
                Source -> "RdCall"
                Both -> "RdCall"
            }
            is Member.Reactive.Signal -> "RdSignal"
            is Member.Reactive.Stateful.Property -> "RdProperty"
            is Member.Reactive.Stateful.List -> "RdList"
            is Member.Reactive.Stateful.Set -> "RdSet"
            is Member.Reactive.Stateful.Map -> "RdMap"
            is Member.Reactive.Stateful.Extension -> fqn(this@Cpp17Generator, flowTransform)

            else -> fail("Unsupported member: $this")
        }


    protected open val Member.Reactive.ctorSimpleName: String
        get () = when (this) {
            is Member.Reactive.Stateful.Extension -> factoryFqn(this@Cpp17Generator, flowTransform)
            else -> implSimpleName
        }


    protected open fun Member.implSubstitutedName(scope: Declaration) = when (this) {
        is Member.EnumConst -> fail("Code must be unreachable for ${javaClass.simpleName}")
        is Member.Field -> type.substitutedName(scope)
        is Member.Reactive -> {
            implSimpleName + (genericParams.toList().map { it.templateName(scope) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")
            /*val isProperty = (this is Member.Reactive.Stateful.Property)
            implSimpleName + (genericParams.toList().map
            { it.substitutedName(scope, omitNullability = isProperty) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")*/
        }
    }

    protected open fun Member.implTemplateName(scope: Declaration) = when (this) {
        is Member.EnumConst -> fail("Code must be unreachable for ${javaClass.simpleName}")
        is Member.Field -> type.templateName(scope)
        is Member.Reactive -> {
            implSimpleName + (genericParams.toList().map { it.templateName(scope) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")
            /*val isProperty = (this is Member.Reactive.Stateful.Property)
            implSimpleName + (genericParams.toList().map
            { it.templateName(scope, isProperty) } + customSerializers(scope)).toTypedArray().joinToOptString(separator = ", ", prefix = "<", postfix = ">")*/
        }
    }


    protected open fun Member.ctorSubstitutedName(scope: Declaration) = when (this) {
        is Member.Reactive.Stateful.Extension -> {
            "rd::" + ctorSimpleName + genericParams.joinToOptString(separator = ", ", prefix = "<", postfix = ">") { it.templateName(scope) }
        }
        else -> implSubstitutedName(scope)
    }


    protected open val Member.isBindable: Boolean
        get() = when (this) {
            is Member.Field -> type is IBindable
            is Member.Reactive -> true

            else -> false
        }


    open val Member.publicName: String get() = name
    open val Member.encapsulatedName: String get() = "${publicName}_"
    open val Member.isEncapsulated: Boolean get() = this is Member.Reactive

    internal fun ctorParam(member: Member, scope: Declaration, withSetter: Boolean): String {
        val typeName = member.implSubstitutedName(scope)
        return StringBuilder().also {
            it.append("$typeName ${member.encapsulatedName}")
            if (withSetter) {
                it.appendDefaultInitialize(member, typeName)
            }
        }.toString()
    }

    protected fun Member.Reactive.customSerializers(scope: Declaration): List<String> {
        return genericParams.asList().map { it.serializerRef(scope, true) }
    }

    protected open val Member.hasEmptyConstructor: Boolean
        get() = when (this) {
            is Member.Field -> type.hasEmptyConstructor && !emptyCtorSuppressed
            is Member.Reactive -> true

            else -> fail("Unsupported member: $this")
        }
    //endregion

    //region Declaration.
    protected fun Declaration.sanitizedName(scope: Declaration): String {
        val needQualification = namespace != scope.namespace
        return needQualification.condstr { "$namespace::" } + name
    }

    private fun Declaration.withNamespace(): String {
        return "$namespace::$name"
    }


    protected fun Declaration.scopeResolution(): String {
        return "$name::"
    }

    internal fun bases(declaration: Declaration): MutableList<BaseClass> {
        val baseName = declaration.baseNames()
        return if (declaration.base == null) {
            val result = mutableListOf<BaseClass>()
            if (declaration !is Toplevel) {
//                result.add("rd::IPolymorphicSerializable" + withMembers.condstr { "()" })
                result.add(BaseClass(IPolymorphicSerializable, emptyList()))
            }
//            baseName?.let { result.add(it) }
            result.addAll(baseName)
            result
        } else {
            val result = baseName.toMutableList()
            if (isUnknown(declaration)) {
//                result.add("rd::IUnknownInstance" + withMembers.condstr { "(std::move(unknownId))" })
                result.add(BaseClass(IUnknownInstance, listOf(Member.Field("unknownId", RdId))))
            }
            result
        }
//        return listOf("ISerializable" + withMembers.condstr { "()" }) + (baseName?.let { listOf(it) } ?: emptyList())
    }

    protected fun Declaration.baseNames(): List<BaseClass> {
        /*return this.base?.let {
            it.sanitizedName(this) + withMembers.condstr {
                "(${it.allMembers.joinToString(", ") { member -> "std::move(${member.encapsulatedName})" }})"
            }
        } ?: (
                (if (this is Toplevel) "rd::RdExtBase"
                else if (this is Class || this is Aggregate || this is Toplevel) "rd::RdBindableBase"
//            else if (decl is Struct) p(" : IPrintable")
                else null)?.plus(withMembers.condstr { "()" }))*/
        return this.base?.let {
            mutableListOf(BaseClass(it as IType, it.allMembers))
            /*it.sanitizedName(this) + withMembers.condstr {
                "(${it.allMembers.joinToString(", ") { member -> "std::move(${member.encapsulatedName})" }})"
            }*/
        } ?: (
                if (this is Toplevel)
                    listOf(BaseClass(RdExtBase, emptyList()))
                else if (this is Class || this is Aggregate || this is Toplevel)
                    listOf(BaseClass(RdBindableBase, emptyList()))
//                else if (decl is Struct) p(" : IPrintable")
                else listOf()
                )
    }

    val Declaration.primaryCtorVisibility: String
        get() {
            val modifier =
                    when {
                        hasSetting(PublicCtors) -> "public"
                        isAbstract -> "protected"
                        hasSecondaryCtor -> "private"
                        isExtension -> "public"
                        this is Toplevel -> "private"
                        else -> "public"
                    } + ":"
            return modifier
        }

    private val Declaration.hasSecondaryCtor: Boolean get () = (this is Toplevel || this.isConcrete) && this.allMembers.any { it.hasEmptyConstructor }
//endregion

    private fun File.cmakeLists(targetName: String, fileNames: List<String>, toplevelsDependencies: List<Toplevel> = emptyList(), subdirectories: List<String> = emptyList()) {
        mkdirs()
        File(this, "CMakeLists.txt").run {
            printWriter().use {
                it.println("cmake_minimum_required(VERSION 3.11)")
                it.println("add_library($targetName STATIC ${fileNames.joinToString(separator = eol)})")
                val toplevelsDirectoryList = toplevelsDependencies.joinToString(separator = " ") { it.name }
                val toplevelsLibraryList = toplevelsDependencies.joinToString(separator = " ") { it.name }
                it.println(subdirectories.map { s -> "add_subdirectory($s)" }.joinToString(separator = eol))
                it.println("target_include_directories($targetName PUBLIC \${CMAKE_CURRENT_SOURCE_DIR} $toplevelsDirectoryList)")
                it.println("target_link_libraries($targetName PUBLIC rd_framework_cpp)")
//                it.println("target_link_directories($targetName PUBLIC rd_framework_cpp $toplevelsLibraryList)")
            }
        }
    }

    override fun generate(root: Root, clearFolderIfExists: Boolean, toplevels: List<Toplevel>) {
        prepareGenerationFolder(folder, clearFolderIfExists)

        val allFilePaths = emptyList<String>().toMutableList()

        toplevels.sortedBy { it.name }.forEach { tl ->
            val types = tl.declaredTypes + tl + unknowns(tl.declaredTypes)
            val directory = tl.fsPath()
            directory.mkdirs()
            val fileNames = types.filter { it !is Enum }.map { it.fsName(true) } + types.map { it.fsName(false) }
            allFilePaths += fileNames.map { "${tl.name}/$it" }

//            directory.cmakeLists(tl.name, fileNames)
            for (type in types) {
                listOf(false, true).forEach { isDefinition ->
                    type.fsPath(tl, isDefinition).run {
                        bufferedWriter().use { writer ->
                            PrettyPrinter().apply {
                                eolKind = Eol.osSpecified
                                step = 4

                                //actual generation

                                if (isDefinition) {
                                    if (type !is Enum) {
                                        source(type, types)
                                    }
                                } else {
                                    header(type)
                                }

                                writer.write(toString())
                            }
                        }
                    }
                }

            }


        }

        folder.cmakeLists(root.targetName(), allFilePaths, toplevels/*, toplevels.map { it.name }*/)
    }


    //region files
    fun PrettyPrinter.header(decl: Declaration) {
        +"#ifndef ${decl.name}_H"
        +"#define ${decl.name}_H"
        println()

        includesDecl()
        println()

        dependenciesDecl(decl)
        println()

        if (decl !is Enum) {
            VsWarningsDefault?.let {
                +"#pragma warning( push )"
                it.forEach {
                    +"#pragma warning( disable:$it )"
                }
            }
        }

        if (decl is Toplevel && decl.isLibrary) {
            comment("library")
            surroundWithNamespaces {
                libdecl(decl)
            }
        } else {
            typedecl(decl)
        }
        println()

        +"#endif // ${decl.name}_H"
    }

    fun PrettyPrinter.source(decl: Declaration, dependencies: List<Declaration>) {
        +decl.include()

        println()
        if (decl is Toplevel) {
            dependencies.filter { !it.isAbstract }.filterIsInstance<IType>().println {
                it.include()
            }
        }
        println()
        if (decl is Toplevel) {
            println("""#include "${decl.root.sanitizedName(decl)}.h"""")
        }
        if (decl.isAbstract) {
            +(unknown(decl)!!.include())
        }
        if (decl is Root) {
            decl.toplevels.forEach {
                +it.include()
            }
        }
        if (decl is Toplevel && decl.isLibrary) {
            surroundWithNamespaces { libdef(decl, decl.declaredTypes + unknowns(decl.declaredTypes)) }
        } else {
            surroundWithNamespaces { typedef(decl) }
        }
    }
//endregion

    //region declaration
    protected open fun PrettyPrinter.libdecl(decl: Declaration) {
        titledBlock("class ${decl.name}") {
            registerSerializersTraitDecl(decl)
        }
    }

    protected open fun PrettyPrinter.typedecl(decl: Declaration) {
        if (decl.documentation != null || decl.ownMembers.any { !it.isEncapsulated && it.documentation != null }) {
            +"/**"
            if (decl.documentation != null) {
                +" * ${decl.documentation}"
            }
            for (member in decl.ownMembers.filter { !it.isEncapsulated && it.documentation != null }) {
                +" * @property ${member.name} ${member.documentation}"
            }
            +" */"
        }

        surroundWithNamespaces {
            if (decl is Enum) {
                enum(decl)
                return@surroundWithNamespaces
            }

            if (decl.isAbstract) comment("abstract")
            if (decl is Struct.Concrete && decl.base == null) comment("data")


            p("class ${decl.name} ")
            baseClassTraitDecl(decl)
            block("{", "};") {
                comment("companion")
                companionTraitDecl(decl)

                if (decl.isExtension) {
                    comment("extension")
                    declare(extensionTraitDecl(decl as Ext))
                }

                comment("custom serializers")
                customSerializersTrait(decl)

                comment("fields")
                +"protected:"
                fieldsDecl(decl)

                comment("initializer")
                private()
                declare(initializerTraitDecl(decl))

                comment("primary ctor")
                //            +(decl.primaryCtorVisibility)
                public()
                declare(primaryCtorTraitDecl(decl))

                comment("secondary constructor")
                declare(secondaryConstructorTraitDecl(decl))

                comment("default ctors and dtors")
                defaultCtorsDtorsDecl(decl)

                comment("reader")
                declare(readerTraitDecl(decl))

                comment("writer")
                declare(writerTraitDecl(decl))

                comment("virtual init")
                declare(virtualInitTraitDecl(decl))

                comment("identify")
                declare(identifyTraitDecl(decl))

                comment("getters")
                declare(gettersTraitDecl(decl))

                comment("intern")
                declare(internTraitDecl(decl))

                comment("equals trait")
                private()
                declare(equalsTraitDecl(decl))

                comment("equality operators")
                public()
                equalityOperatorsDecl(decl)

                comment("hash code trait")
                declare(hashCodeTraitDecl(decl))

                comment("type name trait")
                declare(typenameTraitDecl(decl))
                //            comment("pretty print")
                //            prettyPrintTrait(decl)


                /*if (decl.isExtension) {
                    extensionTraitDef(decl as Ext)
                }*/
            }
        }

        VsWarningsDefault?.let {
            println()
            +"#pragma warning( pop )"
            println()
        }

        comment("hash code trait")
        hashSpecialization(decl)
    }

    protected open fun PrettyPrinter.enum(decl: Enum) {
        titledBlock("enum class ${decl.name}") {
            +decl.constants.joinToString(separator = ",${eolKind.value}") {
                docComment(it.documentation) + it.name
            }
        }
    }

    protected fun primaryCtorParams(decl: Declaration): Constructor.Primary.AllArguments {
        val own = decl.ownMembers
        val base = decl.membersOfBaseClasses
        return Constructor.Primary.AllArguments(own, base.plus(unknownMembers(decl)))
    }

    protected fun secondaryCtorParams(decl: Declaration): Constructor.Secondary.AllArguments {
        var ownMembers = decl.allMembers
                .asSequence()
                .filter { !it.hasEmptyConstructor }.plus(unknownMembersSecondary(decl))
                .toList()
        var membersOfBaseClasses = decl.allMembers
                .asSequence()
                .map {
                    if (ownMembers.contains(it)) {
                        it
                    } else {
                        null
                    }
                }
                .toList()
        /*if (ownMembers.size + membersOfBaseClasses.size == 0) {
            return Constructor.Secondary.AllArguments()
        }*/
        val unknowns = unknownMembersSecondary(decl)
        return Constructor.Secondary.AllArguments(ownMembers, membersOfBaseClasses.plus(unknowns))
    }
//endregion

    //region TraitDecl
    protected fun PrettyPrinter.includesDecl() {
//        +"class ${decl.name};"

        val standardHeaders = listOf(
                "iostream",
                "cstring",
                "cstdint",
                "vector",
                "type_traits",
                "utility"
        )


        val frameworkHeaders = listOf(
                //root
                "Buffer",
                "Identities",
                "MessageBroker",
                "Protocol",
                "RdId",
                //impl
                "RdList",
                "RdMap",
                "RdProperty",
                "RdSet",
                "RdSignal",
                "RName",
                //serialization
                "ISerializable",
                "Polymorphic",
                "NullableSerializer",
                "ArraySerializer",
                "InternedSerializer",
                "SerializationCtx",
                "Serializers",
                "ISerializersOwner",
                "IUnknownInstance",
                //ext
                "RdExtBase",
                //task
                "RdCall",
                "RdEndpoint",
                "RdTask",
                //gen
                "gen_util"
        )

        +frameworkHeaders.joinToString(separator = eol) { s -> s.include() }
        println()
        +standardHeaders.joinToString(separator = eolKind.value, transform = { "#include <$it>" })
        println()
        //third-party
        +"optional".include("hpp")
    }

    fun parseType(type: IType, allowPredefined: Boolean): IType? {
        return when (type) {
            is IArray -> {
                parseType(type.itemType, allowPredefined)
            }
            is IImmutableList -> {
                parseType(type.itemType, allowPredefined)
            }
            is INullable -> {
                parseType(type.itemType, allowPredefined)
            }
            is InternedScalar -> {
                parseType(type.itemType, allowPredefined)
            }
            is Struct -> {
                type
            }
            is Class -> {
                type
            }
            is Enum -> {
                type
            }
            else -> {
                if (allowPredefined && type is PredefinedType) {
                    type
                } else {
                    null
                }
            }
        }
    }

    fun PrettyPrinter.dependenciesDecl(decl: Declaration) {
        fun parseMember(member: Member): List<String> {
            return when (member) {
                is Member.EnumConst -> {
                    arrayListOf()
                }
                is Member.Field -> {
                    parseType(member.type, false)?.let {
                        listOf(it.name)
                    } ?: emptyList()
                }
                is Member.Reactive -> {
                    if (member is Member.Reactive.Stateful.Extension) {
                        arrayListOf(member.fqn(this@Cpp17Generator, flowTransform), parseType(member.delegatedBy, false)!!.name)
                    } else {
                        member.genericParams.fold(arrayListOf<IType>()) { acc, iType ->
                            parseType(iType, false)?.let {
                                acc += it
                            }
                            acc
                        }.map { it.name }
                    }
                }
            }
        }

        fun dependencies(decl: Declaration, extHeader: List<String>): List<String> {
            return decl.ownMembers.asSequence().map { parseMember(it) }.fold(arrayListOf<String>()) { acc, arrayList ->
                acc += arrayList
                acc
            }.plus(listOfNotNull(decl.base?.name)).plus(extHeader)
                    //                .filter { dependencies.map { it.name }.contains(it) }
                    .distinct().toList()
        }

        val extHeader = listOfNotNull(if (decl.isExtension) decl.pointcut?.name else null)
        dependencies(decl, extHeader).printlnWithBlankLine { it.include() }
    }


    fun PrettyPrinter.baseClassTraitDecl(decl: Declaration) {
        +bases(decl).joinToString(separator = ", ", prefix = ": ") { "public ${it.type.name}" }
    }


    protected fun createMethodTraitDecl(decl: Toplevel): Signature? {
        if (decl.isExtension) return null
        return MemberFunction("void", "connect(rd::Lifetime lifetime, rd::IProtocol const * protocol)", decl.name)
    }

    fun PrettyPrinter.customSerializersTrait(decl: Declaration) {
        fun IType.serializerBuilder(): String = leafSerializerRef(decl) ?: "rd::" + when (this) {
            is IArray -> "ArraySerializer<${itemType.serializerBuilder()}>"
            is IImmutableList -> "ArraySerializer<${itemType.serializerBuilder()}>"
            is INullable -> "NullableSerializer<${itemType.serializerBuilder()}>"
            is InternedScalar -> """InternedSerializer<${itemType.serializerBuilder()}, ${internKey.hash()}>"""
            else -> fail("Unknown type: $this")
        }

        private()
        val allTypesForDelegation = decl.allMembers
                .filterIsInstance<Member.Reactive>()
                .flatMap { it.genericParams.toList() }
                .distinct()
                .filter { it.leafSerializerRef(decl) == null }

        allTypesForDelegation.println { "using ${it.serializerRef(decl, false)} = ${it.serializerBuilder()};" }
    }


    private fun abstractDeclarationTraitDecl(decl: Declaration): MemberFunction {
        return MemberFunction(decl.name.wrapper(), "readUnknownInstance(rd::SerializationCtx const& ctx, rd::Buffer const &buffer, rd::RdId const &unknownId, int32_t size)", decl.name).override()
    }

    protected fun PrettyPrinter.registerSerializersTraitDecl(decl: Declaration) {
        val serializersOwnerImplName = "${decl.name}SerializersOwner"
        public()
        block("struct $serializersOwnerImplName : public rd::ISerializersOwner {", "};") {
            declare(MemberFunction("void", "registerSerializersCore(rd::Serializers const& serializers)", decl.name).const().override())
        }
        println()
        +"static const $serializersOwnerImplName serializersOwner;"
        println()
    }

    protected fun PrettyPrinter.companionTraitDecl(decl: Declaration) {
        if (isUnknown(decl)) {
            +"friend class ${decl.name.dropLast(8)};"
            //todo drop "_Unknown" smarter
        }
        /*if (decl.isAbstract) {
            println()
            declare(abstractDeclarationTraitDecl(decl))
        }*/
        if (decl is Toplevel) {
            println()
            registerSerializersTraitDecl(decl)
            println()
            public()
            declare(createMethodTraitDecl(decl))
            println()
        }
    }


    protected fun extensionTraitDecl(decl: Ext): MemberFunction? {
        val pointcut = decl.pointcut ?: return null
        val lowerName = decl.name.decapitalize()
        val extName = decl.extName ?: lowerName
        return MemberFunction("""${decl.name} const &""", "getOrCreateExtensionOf(${pointcut.sanitizedName(decl)} & pointcut)", decl.name).static()
    }

    fun PrettyPrinter.fieldsDecl(decl: Declaration) {
        val own = decl.ownMembers.map {
            val initial = getDefaultValue(decl, it)?.let {
                "{$it}"
            } ?: ""
            "${ctorParam(it, decl, true)}$initial"
        }

        val unknowns = unknownMembers(decl).map { "${it.type.name} ${it.encapsulatedName}" }
        +own.asSequence().plus(unknowns).joinToString(separator = "") { "$it${carry()}" }

        if (decl is Class && decl.isInternRoot) {
            +"mutable tl::optional<rd::SerializationCtx> mySerializationContext;"
        }
    }

    fun initializerTraitDecl(decl: Declaration): MemberFunction {
        return MemberFunction("void", "initialize()", decl.name)
    }

    private fun primaryCtorTraitDecl(decl: Declaration): Constructor? {
        val arguments = primaryCtorParams(decl)
        return if (arguments.isEmpty()) {
            null
        } else {
            Constructor.Primary(this, decl, arguments)
        }
    }

    private fun secondaryConstructorTraitDecl(decl: Declaration): Constructor? {
        if (!decl.hasSecondaryCtor) return null

        val members = decl.allMembers
                .asSequence()
                .filter { !it.hasEmptyConstructor }
        if (members.count() == 0) {
            return null
        }
//        +"explicit ${decl.name}"
        val arguments = secondaryCtorParams(decl)
        return Constructor.Secondary(this, decl, arguments)
    }

    fun PrettyPrinter.defaultCtorsDtorsDecl(decl: Declaration): Constructor {
        val name = decl.name
        println()

        val constructor = Constructor.Default(this@Cpp17Generator, decl)
        declare(constructor)

        if (decl is IScalar) {
            println()
            +"$name($name const &) = default;"
            println()
            +"$name& operator=($name const &) = default;"
        }
        println()
        if (decl is Toplevel) {
            +"$name($name &&) = delete;"
            println()
            +"$name& operator=($name &&) = delete;"
        } else {
            +"$name($name &&) = default;"
            println()
            +"$name& operator=($name &&) = default;"
        }
        println()
        +"virtual ~$name() = default;"
        return constructor
    }

    private fun readerTraitDecl(decl: Declaration): Signature? {
        return when {
            decl.isConcrete -> MemberFunction(decl.name, "read(rd::SerializationCtx const& ctx, rd::Buffer const & buffer)", decl.name).static()
            decl.isAbstract -> MemberFunction(decl.name.wrapper(), "readUnknownInstance(rd::SerializationCtx const& ctx, rd::Buffer const & buffer, rd::RdId const& unknownId, int32_t size)", decl.name).static()
            else -> null
        }
    }

    protected fun writerTraitDecl(decl: Declaration): MemberFunction? {
        val signature = MemberFunction("void", "write(rd::SerializationCtx const& ctx, rd::Buffer const& buffer)", decl.name).const()

        return when {
            decl is Toplevel -> return null
            decl.isConcrete -> signature.override()
            else -> signature.abstract().override()
        }
    }

    protected fun virtualInitTraitDecl(decl: Declaration): MemberFunction? {
        if (decl !is BindableDeclaration) {
            return null
        }
        return MemberFunction("void", "init(rd::Lifetime lifetime)", decl.name).const().override()
    }

    protected fun identifyTraitDecl(decl: Declaration): MemberFunction? {
        if (decl !is BindableDeclaration) {
            return null
        }
        return MemberFunction("void", "identify(const rd::Identities &identities, rd::RdId const &id)", decl.name).const().override()
    }

    protected fun gettersTraitDecl(decl: Declaration): List<MemberFunction> {
        return decl.ownMembers.map { member -> MemberFunction("${member.implTemplateName(decl)} const &", "${member.getter()}()", decl.name).const() }
    }

    protected fun internTraitDecl(decl: Declaration): MemberFunction? {
        return if (decl is Class && decl.isInternRoot) {
            return MemberFunction("const rd::SerializationCtx &", "get_serialization_context()", decl.name).const().override()
        } else {
            null
        }
    }

    protected fun equalsTraitDecl(decl: Declaration): MemberFunction? {
//        val signature = MemberFunction("bool", "equals(${decl.name} const& other)", decl.name).const()
        val signature = MemberFunction("bool", "equals(rd::ISerializable const& object)", decl.name).const()
        return if (decl is Toplevel || decl.isAbstract) {
            null
        } else {
            signature.override()
        }
        /*return if (decl.isAbstract) {
            signature.abstract(decl)
        } else {
            if (decl is Toplevel) {
                signature
            } else {
                signature.override()
            }
        }*/
    }

    protected fun PrettyPrinter.equalityOperatorsDecl(decl: Declaration) {
//        if (decl.isAbstract || decl !is IScalar) return

        +("friend bool operator==(const ${decl.name} &lhs, const ${decl.name} &rhs);")
        +("friend bool operator!=(const ${decl.name} &lhs, const ${decl.name} &rhs);")
    }

    protected fun hashCodeTraitDecl(decl: Declaration): MemberFunction? {
        if (decl !is IScalar) return null

        val signature = MemberFunction("size_t", "hashCode()", decl.name).const()
        return when {
            decl is Toplevel -> return null
            decl.isConcrete -> signature.override()
            else -> signature.abstract().override()
        }
    }

    protected fun typenameTraitDecl(decl: Declaration): MemberFunction? {
        return if (decl !is Toplevel) {
            MemberFunction("std::string", "type_name()", decl.name).const().override()
        } else {
            null
        }
    }

    protected fun PrettyPrinter.hashSpecialization(decl: Declaration) {
        if (decl !is IScalar) return
        if (decl is Enum) return

        block("namespace std {", "}") {
            block("template <> struct hash<${decl.withNamespace()}> {", "};") {
                block("size_t operator()(const ${decl.withNamespace()} & value) const {", "}") {
                    +"return value.hashCode();"
                }
            }
        }
    }

//endregion

    //region definition
    protected open fun PrettyPrinter.libdef(decl: Toplevel, types: List<Declaration>) {
        registerSerializersTraitDef(decl, types)
    }

    protected fun PrettyPrinter.typedef(decl: Declaration) {
        comment("companion")
        companionTraitDef(decl)

        if (decl.isExtension) {
            comment("extension")
            extensionTraitDef(decl as Ext)
        }

        comment("initializer")
        initializerTraitDef(decl)

        comment("primary ctor")
        primaryCtorTraitDef(decl)

        comment("secondary constructor")
        secondaryConstructorTraitDef(decl)

        comment("default ctors and dtors")
        defaultCtorsDtorsDef(decl)

        comment("reader")
        readerTraitDef(decl)

        comment("writer")
        writerTraitDef(decl)

        comment("virtual init")
        virtualInitTraitDef(decl)

        comment("identify")
        identifyTraitDef(decl)

        comment("getters")
        gettersTraitDef(decl)

        comment("intern")
        internTraitDef(decl)

        comment("equals trait")
        equalsTraitDef(decl)

        comment("equality operators")
        equalityOperatorsDef(decl)

        comment("hash code trait")
        hashCodeTraitDef(decl)

        typenameTraitDef(decl)
//        comment("pretty print")
//            prettyPrintTrait(decl)

    }

    private fun PrettyPrinter.readerBodyTrait(decl: Declaration) {
        fun IType.reader(): String = when (this) {
            is Enum -> "buffer.readEnum<${templateName(decl)}>()"
            is InternedScalar -> {
                val lambda = lambda("rd::SerializationCtx const &, rd::Buffer const &", "return ${itemType.reader()}")
                """ctx.readInterned<${itemType.templateName(decl)}, ${internKey.hash()}>(buffer, $lambda)"""
            }
            is PredefinedType.void -> "rd::Void()" //really?
            is PredefinedType.rdId -> "rd::RdId()"
            is PredefinedType.bool -> "buffer.readBool()"
            is PredefinedType.string -> "buffer.readWString()"
            in PredefinedIntegrals -> "buffer.read_integral<${templateName(decl)}>()"
            in PredefinedFloating -> "buffer.read_floating_point<${templateName(decl)}>()"
            is PredefinedType -> "buffer.read${name.capitalize()}()"
            is Declaration ->
                if (isAbstract)
                    "ctx.get_serializers().readPolymorphic<${templateName(decl)}>(ctx, buffer)"
                else
                    "${templateName(decl)}::read(ctx, buffer)"
            is INullable -> {
                val lambda = lambda(null, "return ${itemType.reader()}")
                """buffer.readNullable<${itemType.templateName(decl)}>($lambda)"""
            }
            is IArray, is IImmutableList -> { //awaiting superinterfaces' support in Kotlin
                this as IHasItemType
                if (isPrimitivesArray) {
                    "buffer.readArray<${itemType.templateName(decl)}>()"
                } else {
                    """buffer.readArray<${itemType.templateName(decl)}>(${lambda(null, "return ${itemType.reader()}")})"""
                }
            }
            else -> fail("Unknown declaration: $decl")
        }

        fun Member.reader(): String = when (this) {
            is Member.Field -> type.reader()
//            is Member.Reactive.Stateful.Extension -> "${ctorSubstitutedName(decl)}(${delegatedBy.reader()})"
            is Member.Reactive.Stateful.Extension -> {
                "${ctorSubstitutedName(decl)}{}"
            }
            is Member.Reactive -> {
                val params = listOf("ctx", "buffer").joinToString(", ")
                "${implSubstitutedName(decl)}::read($params)"
            }
            else -> fail("Unknown member: $this")
        }

        fun Member.valName(): String = encapsulatedName.let { it + (it == "ctx" || it == "buffer").condstr { "_" } }

        val unknown = isUnknown(decl)
        if (unknown) {
            +"int32_t objectStartPosition = buffer.get_position();"
        }
        if (decl is Class || decl is Aggregate) {
            +"auto _id = rd::RdId::read(buffer);"
        }
        (decl.membersOfBaseClasses + decl.ownMembers).println { "auto ${it.valName()} = ${it.reader()};" }
        if (unknown) {
            +"auto unknownBytes = rd::Buffer::ByteArray(objectStartPosition + size - buffer.get_position());"
            +"buffer.readByteArrayRaw(unknownBytes);"
        }
        val ctorParams = decl.allMembers.asSequence().map { "std::move(${it.valName()})" }.plus(unknownMemberNames(decl)).joinToString(", ")
//        p("return ${decl.name}($ctorParams)${(decl is Class && decl.isInternRoot).condstr { ".apply { mySerializationContext = ctx }" }}")
        +"${decl.name} res{${ctorParams.isNotEmpty().condstr { ctorParams }}};"
        if (decl is Class || decl is Aggregate) {
            +"withId(res, _id);"
        }
        if (decl is Class && decl.isInternRoot) {
            +"res.mySerializationContext = ${decl.withInternRootsHere("res")};"
        }
        +"return res;"
    }

    private fun lambda(args: String?, body: String, resultType: String? = null): String {
        val typeHint = resultType?.let { " -> $resultType" } ?: ""
        return "$eol[&ctx, &buffer](${args ?: ""})$typeHint $eol{ $body; }$eol"
    }
//endregion

//region TraitDef

    private fun PrettyPrinter.abstractDeclarationTraitDef(decl: Declaration) {
        define(abstractDeclarationTraitDecl(decl)) {
            readerBodyTrait(unknown(decl)!!)
        }
    }

    protected fun PrettyPrinter.registerSerializersTraitDef(decl: Toplevel, types: List<Declaration>) {//todo name access
        val serializersOwnerImplName = "${decl.name}SerializersOwner"
        +"${decl.name}::$serializersOwnerImplName const ${decl.name}::serializersOwner;"
        println()
        define(MemberFunction("void", "registerSerializersCore(rd::Serializers const& serializers)", "${decl.name}::${decl.name}SerializersOwner").const().override()) {
            types.filter { !it.isAbstract }.filterIsInstance<IType>().filterNot { iType -> iType is Enum }.println {
                "serializers.registry<${it.name}>();"
            }

            if (decl is Root) {
                decl.toplevels.minus(decl).println {
                    val name = it.sanitizedName(decl)
                    "$name::serializersOwner.registry(serializers);"
                }
                //todo mark graph vertex
            }
        }
    }

    //only for toplevel Exts
    protected fun PrettyPrinter.createMethodTraitDef(decl: Toplevel) {
        define(createMethodTraitDecl(decl)) {
            +"${decl.root.sanitizedName(decl)}::serializersOwner.registry(protocol->get_serializers());"
            println()

//            +"${decl.name} res;"
            val quotedName = """"${decl.name}""""
            +"identify(*(protocol->get_identity()), rd::RdId::Null().mix($quotedName));"
            +"bind(lifetime, protocol, $quotedName);"
//            +"return res;"
        }
    }

    protected fun PrettyPrinter.companionTraitDef(decl: Declaration) {
        /*if (decl.isAbstract) {
            println()
            abstractDeclarationTraitDef(decl)
        }*/
        if (decl is Toplevel) {
            println()
            registerSerializersTraitDef(decl, decl.declaredTypes + unknowns(decl.declaredTypes))
            println()
            createMethodTraitDef(decl)

            println()
        }
    }


    fun PrettyPrinter.primaryCtorTraitDef(decl: Declaration) {
        define(primaryCtorTraitDecl(decl)) {
            +"initialize();"
        }
    }

    private fun PrettyPrinter.secondaryConstructorTraitDef(decl: Declaration) {
        define(secondaryConstructorTraitDecl(decl)) {
            +"initialize();"
        }
    }

    fun PrettyPrinter.defaultCtorsDtorsDef(decl: Declaration) {
        define(Constructor.Default(this@Cpp17Generator, decl)) {
            +"initialize();"
        }
    }

    protected fun PrettyPrinter.readerTraitDef(decl: Declaration) {
        define(readerTraitDecl(decl)) {
            if (decl.isConcrete) {
                if (isUnknown(decl)) {
                    +"""throw std::logic_error("Unknown instances should not be read via serializer");"""
                } else {
                    readerBodyTrait(decl)
                }
            } else if (decl.isAbstract) {
                readerBodyTrait(unknown(decl)!!)
            }
        }
    }

    protected fun IType.nestedOf(decl: Declaration): Boolean {
        val iType = parseType(this, true)
        return iType?.name == decl.name
    }

    protected fun PrettyPrinter.writerTraitDef(decl: Declaration) {
        fun IType.writer(field: String): String {
            return when (this) {
                is Enum -> "buffer.writeEnum($field)"
                is InternedScalar -> {
                    val lambda = lambda("rd::SerializationCtx const &, rd::Buffer const &, ${itemType.substitutedName(decl)} const & internedValue", itemType.writer("internedValue"), "void")
                    """ctx.writeInterned<${itemType.templateName(decl)}, ${internKey.hash()}>(buffer, $field, $lambda)"""
                }
                is PredefinedType.void -> "" //really?
                is PredefinedType.bool -> "buffer.writeBool($field)"
                is PredefinedType.string -> "buffer.writeWString($field)"
                in PredefinedIntegrals -> "buffer.write_integral($field)"
                in PredefinedFloating -> "buffer.write_floating_point($field)"
                is Declaration ->
                    if (isAbstract)
                        "ctx.get_serializers().writePolymorphic<${templateName(decl)}>(ctx, buffer, $field)"
                    else {
                        "rd::Polymorphic<std::decay_t<decltype($field)>>::write(ctx, buffer, $field)"
                    }
                is INullable -> {
                    val lambda = lambda("${itemType.substitutedName(decl)} const & it", itemType.writer("it"), "void")
                    "buffer.writeNullable<${itemType.templateName(decl)}>($field, $lambda)"
                }
                is IArray, is IImmutableList -> { //awaiting superinterfaces' support in Kotlin
                    this as IHasItemType
                    if (isPrimitivesArray) {
                        "buffer.writeArray($field)"
                    } else {
                        val lambda = lambda("${itemType.substitutedName(decl)} const & it", itemType.writer("it"), "void")
                        "buffer.writeArray<${itemType.substitutedName(decl)}>($field, $lambda)"
                    }
                }
                else -> fail("Unknown declaration: $decl")
            }
        }


        fun Member.writer(): String = when (this) {
            is Member.Field -> type.writer(encapsulatedName)
//            is Member.Reactive.Stateful.Extension -> delegatedBy.writer((encapsulatedName))//todo
            is Member.Reactive.Stateful.Extension -> ""
            is Member.Reactive -> "$encapsulatedName.write(ctx, buffer)"

            else -> fail("Unknown member: $this")
        }

        if (decl.isConcrete) {
            define(writerTraitDecl(decl)) {
                if (decl is Class || decl is Aggregate) {
                    +"this->rdid.write(buffer);"
                }
                (decl.membersOfBaseClasses + decl.ownMembers).println { member -> member.writer() + ";" }
                if (isUnknown(decl)) {
                    +"buffer.writeByteArrayRaw(unknownBytes_);"
                }
                if (decl is Class && decl.isInternRoot) {
                    +"this->mySerializationContext = ${decl.withInternRootsHere("*this")};"
                }
            }
        } else {
            //todo ???
        }
    }

    fun PrettyPrinter.virtualInitTraitDef(decl: Declaration) {
        virtualInitTraitDecl(decl)?.let {
            define(it) {
                val base = "rd::" + (if (decl is Toplevel) "RdExtBase" else "RdBindableBase")
                +"$base::init(lifetime);"
                decl.ownMembers
                        .filter { it.isBindable }
                        .println { """bindPolymorphic(${it.encapsulatedName}, lifetime, this, "${it.name}");""" }
            }
        }
    }

    fun PrettyPrinter.identifyTraitDef(decl: Declaration) {
        identifyTraitDecl(decl)?.let {
            define(it) {
                +"rd::RdBindableBase::identify(identities, id);"
                decl.ownMembers
                        .filter { it.isBindable }
                        .println { """identifyPolymorphic(${it.encapsulatedName}, identities, id.mix(".${it.name}"));""" }
            }
        }
    }

    protected fun PrettyPrinter.gettersTraitDef(decl: Declaration) {
        gettersTraitDecl(decl).zip(decl.ownMembers) { s: MemberFunction, member: Member ->
            define(s) {
                p(docComment(member.documentation))
                val unwrap = when {
                    member is Member.Reactive -> false
                    member is IBindable -> true
                    member is Member.Field && member.type is Struct -> true
                    member is Member.Field && member.type is Class -> true
                    member is Member.Field && member.type is PredefinedType.string -> true
                    member is Member.Field && member.type is InternedScalar && member.type.itemType is PredefinedType.string -> true
                    else -> false
                }
                val star = unwrap.condstr { "*" }
                +"return $star${member.encapsulatedName};"
                /*if (member is Member.Field) {
                    +"return wrapper::get<${member.implTemplateName(decl)}>(${member.encapsulatedName});"
                }*/
            }
        }
    }

    protected fun PrettyPrinter.internTraitDef(decl: Declaration) {
        define(internTraitDecl(decl)) {
            +"""if (mySerializationContext) {
                    |   return *mySerializationContext;
                    |} else {
                    |   throw std::invalid_argument("Attempting to get serialization context too soon for");
                    |}""".trimMargin()
        }
    }

    protected fun PrettyPrinter.initializerTraitDef(decl: Declaration) {
        define(initializerTraitDecl(decl)) {
            decl.ownMembers
                    .filterIsInstance<Member.Reactive.Stateful>()
                    .filter { it !is Member.Reactive.Stateful.Extension && it.genericParams.none { it is IBindable } }
                    .println { "${it.encapsulatedName}.optimize_nested = true;" }

            if (flowTransform == FlowTransform.Reversed) {
                decl.ownMembers
                        .filterIsInstance<Member.Reactive.Stateful.Map>()
                        .println { "${it.encapsulatedName}.master = false;" }
            }

            decl.ownMembers
                    .filterIsInstance<Member.Reactive>()
                    .filter { it.freeThreaded }
                    .println { "${it.encapsulatedName}.async = true;" }

            /*decl.ownMembers
                    .filter { it.isBindable }
                    .println { """bindable_children.emplace_back("${it.name}", &${it.encapsulatedName});""" }*/

            if (decl is Toplevel) {
                +"serializationHash = ${decl.serializationHash(IncrementalHash64()).result}L;"
            }
        }
    }


    private fun PrettyPrinter.equalsTraitDef(decl: Declaration) {
        define(equalsTraitDecl(decl)) {
            +"auto const &other = dynamic_cast<${decl.name} const&>(object);"
            if (decl.isAbstract || decl !is IScalar) {
                +"return this == &other;"
            } else {
                +"if (this == &other) return true;"

                decl.allMembers.println { m ->
                    val f = m as? Member.Field ?: fail("Must be field but was `$m`")
                    val t = f.type as? IScalar ?: fail("Field $decl.`$m` must have scalar type but was ${f.type}")

                    if (f.usedInEquals)
                    //                    "if (${t.eq(f.encapsulatedName)}) return false"
                        "if (this->${f.encapsulatedName} != other.${f.encapsulatedName}) return false;"
                    else
                        ""
                }
                println()
                +"return true;"
            }
        }
    }

    private fun PrettyPrinter.equalityOperatorsDef(decl: Declaration) {
        p("bool operator==(const ${decl.name} &lhs, const ${decl.name} &rhs)")
        if (decl.isAbstract || decl !is IScalar) {
            braceBlock {
                +"return &lhs == &rhs;"
            }
        } else {
            braceBlock {
                +"if (lhs.type_name() != rhs.type_name()) return false;"
                +"return lhs.equals(rhs);"
            }
        }

        p("bool operator!=(const ${decl.name} &lhs, const ${decl.name} &rhs)")
        braceBlock {
            +"return !(lhs == rhs);"
        }
    }

    protected fun PrettyPrinter.hashCodeTraitDef(decl: Declaration) {
        fun IScalar.hc(v: String): String = when (this) {
            is IArray, is IImmutableList ->
                if (isPrimitivesArray) "rd::contentHashCode($v)"
                else "rd::contentDeepHashCode($v)"
            is INullable -> {
                "((bool)$v) ? " + (itemType as IScalar).hc("*$v") + " : 0"
            }
            else -> {
                if (this.isAbstract()) {
                    "std::hash<${this.templateName(decl)}>()($v)"
                } else {
                    "std::hash<${this.templateName(decl)}>()($v)"
                }
            }
        }

        hashCodeTraitDecl(decl)?.let {
            define(it) {
                +"size_t __r = 0;"

                decl.allMembers.println { m: Member ->
                    val f = m as? Member.Field ?: fail("Must be field but was `$m`")
                    val t = f.type as? IScalar ?: fail("Field $decl.`$m` must have scalar type but was ${f.type}")
                    if (f.usedInEquals)
                        "__r = __r * 31 + (${t.hc("""${f.getter()}()""")});"
                    else
                        ""
                }

                +"return __r;"
            }
        }
    }

    protected fun PrettyPrinter.typenameTraitDef(decl: Declaration) {
        typenameTraitDecl(decl)?.let {
            define(it) {
                +"""return "${decl.name}";"""
            }
        }
    }

    protected fun PrettyPrinter.extensionTraitDef(decl: Ext) {//todo
        define(extensionTraitDecl(decl)) {
            val lowerName = decl.name.decapitalize()
            +"""return pointcut.getOrCreateExtension<${decl.name}>("$lowerName");"""
            println()
        }

    }
//endregion

    //region unknowns
    protected fun isUnknown(decl: Declaration) =
            decl is Class.Concrete && decl.isUnknown ||
                    decl is Struct.Concrete && decl.isUnknown

    protected fun unknownMembers(decl: Declaration): List<Member.Field> =
            if (isUnknown(decl)) listOf(
                    Member.Field("unknownId", RdId),
                    Member.Field("unknownBytes", ByteArray))//todo bytearray
            else emptyList()

    private fun unknownMembersSecondary(decl: Declaration) = unknownMembers(decl)

    protected fun unknownMemberNames(decl: Declaration) = unknownMembers(decl).map { it.name }


    override fun unknown(it: Declaration): Declaration? = super.unknown(it)?.setting(PublicCtors)
//endregion

    protected fun docComment(doc: String?) = (doc != null).condstr {
        "\n" +
                "/**\n" +
                " * $doc\n" +
                " */\n"
    }

    protected fun getDefaultValue(containing: Declaration, member: Member): String? =
            when (member) {
                is Member.Reactive.Stateful.Property -> when {
                    member.defaultValue is String -> """L"${member.defaultValue}""""
                    member.defaultValue != null -> {
                        val default = member.defaultValue.toString()
                        if (member.genericParams[0] is PredefinedType.string) {
                            "L$default"
                        } else {
                            default
                        }
                    }
                    else -> null
                }
//                is Member.Reactive.Stateful.Extension -> member.delegatedBy.sanitizedName(containing) + "()"
                else -> null
            }


    override fun toString(): String {
        return "Cpp17Generator(flowTransform=$flowTransform, defaultNamespace='$defaultNamespace', folder=${folder.canonicalPath})"
    }

    val PredefinedIntegrals = listOf(
            PredefinedType.byte,
            PredefinedType.short,
            PredefinedType.int,
            PredefinedType.long,
            PredefinedType.char,
            PredefinedType.bool
    )

    val PredefinedFloating = listOf(
            PredefinedType.float,
            PredefinedType.double
    )
}