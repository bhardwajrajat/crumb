/*
 * Copyright (c) 2017. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.synapse.extensions

import com.google.common.collect.ImmutableSet
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.NameAllocator
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeVariableName
import com.squareup.javapoet.WildcardTypeName
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.uber.synapse.ExtensionArgs
import com.uber.synapse.ExtensionArgsInput
import com.uber.synapse.MoshiTypes
import com.uber.synapse.annotations.Neuron
import com.uber.synapse.asPackageAndName
import com.uber.synapse.implementsInterface
import com.uber.synapse.rawType
import java.lang.Exception
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.TypeElement
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.properties.Delegates

/**
 * Moshi support for Synapse.
 */
class MoshiSupport : SynapseExtension {

  companion object {
    private const val AV_PREFIX = "AutoValue_"
    private const val EXTRAS_KEY = "synapse.extensions.moshi"

    private val ADAPTER_CLASS_NAME = ClassName.get(JsonAdapter::class.java)
    private val FACTORY_CLASS_NAME = ClassName.get(JsonAdapter.Factory::class.java)
    private val TYPE_SPEC = ParameterSpec.builder(Type::class.java, "type").build()
    private val WILDCARD_TYPE_NAME = WildcardTypeName.subtypeOf(Annotation::class.java)
    private val ANNOTATIONS_SPEC = ParameterSpec.builder(
        ParameterizedTypeName.get(ClassName.get(Set::class.java), WILDCARD_TYPE_NAME),
        "annotations")
        .build()
    private val MOSHI_SPEC = ParameterSpec.builder(Moshi::class.java, "moshi").build()
    private val FACTORY_RETURN_TYPE_NAME = ParameterizedTypeName.get(ADAPTER_CLASS_NAME,
        WildcardTypeName.subtypeOf(TypeName.OBJECT))
  }

  private val META_MAP_ADAPTER = Moshi.Builder()
      .add(MoshiSupportMetaAdapter.FACTORY)
      .build()
      .adapter<Map<ModelName, MoshiSupportMeta>>(
          MoshiTypes.newParameterizedType(
              Map::class.java,
              String::class.java,
              MoshiSupportMeta::class.java))

  override fun isApplicable(processingEnv: ProcessingEnvironment, type: TypeElement): Boolean {
    // check that the class contains a public static method returning a JsonAdapter
    val jsonAdapterType = ParameterizedTypeName.get(
        ADAPTER_CLASS_NAME, TypeName.get(type.asType()))
    val returnedJsonAdapter: TypeName = ElementFilter.methodsIn(type.enclosedElements)
        .filter { it.modifiers.containsAll(setOf(Modifier.STATIC, PUBLIC)) }
        .find { method ->
          val rType = method.returnType
          val returnType = TypeName.get(rType)
          if (returnType == jsonAdapterType || returnType == FACTORY_CLASS_NAME) {
            return true
          }

          return@find returnType == jsonAdapterType.rawType || returnType is ParameterizedTypeName && returnType.rawType == jsonAdapterType.rawType
        }?.let { TypeName.get(it.returnType) } ?: return false

    // emit a warning if the user added a method returning a JsonAdapter, but not of the right type
    if (returnedJsonAdapter is ParameterizedTypeName) {
      if (returnedJsonAdapter.typeArguments[0] is ParameterizedTypeName) {
        return true
      } else {
        val argument = returnedJsonAdapter.typeArguments[0]
        processingEnv.messager.printMessage(Diagnostic.Kind.WARNING,
            String.format(
                "Found public static method returning JsonAdapter<%s> on %s class. Skipping MoshiJsonAdapter generation.",
                argument, type))
      }
    } else {
      processingEnv.messager.printMessage(Diagnostic.Kind.WARNING,
          "Found public static method returning JsonAdapter with no type arguments, skipping MoshiJsonAdapter generation.")
    }

    return false
  }

  override fun isTypeSupported(elementUtils: Elements, typeUtils: Types,
      type: TypeElement): Boolean {
    return type.implementsInterface<JsonAdapter.Factory>(elementUtils, typeUtils)
  }

  override fun createSynapseImplementationMethod(elements: List<Element>,
      extras: ExtensionArgsInput): MethodSpec {
    val type = TYPE_SPEC
    val annotations = ANNOTATIONS_SPEC
    val moshi = MOSHI_SPEC

    val create = MethodSpec.methodBuilder("create")
        .addModifiers(PUBLIC)
        .addAnnotation(Override::class.java)
        .addParameters(ImmutableSet.of(type, annotations, moshi))
        .returns(FACTORY_RETURN_TYPE_NAME)

    var classes: CodeBlock.Builder? = null
    var generics: CodeBlock.Builder? = null
    var factories: CodeBlock.Builder? = null

    var numGenerics = 0
    var numClasses = 0
    var numFactories = 0

    // Avoid providing an adapter for an annotated type.
    create.addStatement("if (!\$N.isEmpty()) return null", annotations)

    val modelsMap = mutableMapOf<String, MoshiSupportMeta>()
    for (element in elements) {
      val elementType = element.rawType()
      val fqcn = elementType.toString()
      val factoryMethod = getJsonAdapterFactoryMethod(element)
      if (factoryMethod != null) {
        val factoryMethodName = factoryMethod.simpleName.toString()
        factories = factories ?: CodeBlock.builder()
        if (numFactories == 0) {
          factories!!.addStatement("\$T adapter", FACTORY_RETURN_TYPE_NAME)
          factories.beginControlFlow(
              "if ((adapter = \$L.\$L().create(type, annotations, moshi)) != null)",
              element,
              factoryMethod.simpleName)
        } else {
          factories!!.nextControlFlow(
              "else if ((adapter = \$L.\$L().create(type, annotations, moshi)) != null)",
              element,
              factoryMethod.simpleName)
        }
        factories.addStatement("return adapter")
        numFactories++
        modelsMap.put(fqcn, MoshiSupportMeta(factoryMethodName, isFactory = true))
        continue
      }
      val elementTypeName = TypeName.get(element.asType())

      if (elementTypeName is ParameterizedTypeName) {
        generics = generics ?: CodeBlock.builder()
            .beginControlFlow("if (\$N instanceof \$T)", type, ParameterizedType::class.java)
            .addStatement("\$T rawType = ((\$T) \$N).getRawType()", Type::class.java,
                ParameterizedType::class.java, type)

        addControlFlowGeneric(generics!!, elementTypeName, element, numGenerics)
        numGenerics++
      } else {
        val jsonAdapterMethod = getJsonAdapterMethod(element)
        if (jsonAdapterMethod != null) {
          val adapterMethodName = jsonAdapterMethod.simpleName.toString()
          classes = classes ?: CodeBlock.builder()

          addControlFlow(classes!!, CodeBlock.of("\$N", type), elementTypeName, numClasses)
          numClasses++

          val paramsCount = jsonAdapterMethod.parameters.size
          if (paramsCount == 1) {
            classes.addStatement("return \$T.\$L(\$N)", element, jsonAdapterMethod.simpleName,
                moshi)
          } else {
            // No arg factory
            classes.addStatement("return \$L.\$L()", element.simpleName,
                jsonAdapterMethod.simpleName)
          }
          modelsMap.put(fqcn, MoshiSupportMeta(adapterMethodName, argCount = paramsCount))
        }
      }
    }

    generics?.apply {
      endControlFlow()
      addStatement("return null")
      endControlFlow()
      create.addCode(build())
    }

    classes?.apply {
      endControlFlow()
      create.addCode(build())
    }

    factories?.apply {
      endControlFlow()
      create.addCode(build())
    }

    create.addStatement("return null")
    extras.put(EXTRAS_KEY, META_MAP_ADAPTER.toJson(modelsMap))
    return create.build()
  }

  override fun createCortexImplementationMethod(extras: Set<ExtensionArgs>): Set<MethodSpec> {
    // Get a mapping of model names -> GsonSupportMeta
    val metaMaps = extras
        .filter { it.contains(EXTRAS_KEY) }
        .map { META_MAP_ADAPTER.fromJson(it[EXTRAS_KEY]!!)!! }
        .flatMap { it.entries }
        .associateBy({ it.key }, { it.value })

    // Organize them by package, so packageName -> Map<ModelName, GsonSupportMeta>
    val modelsByPackage = mutableMapOf<String, MutableMap<String, MoshiSupportMeta>>()
    metaMaps.entries
        .forEach {
          val (packageName, name) = it.key.asPackageAndName()
          modelsByPackage.getOrPut(packageName, { mutableMapOf() }).put(name, it.value)
        }

    val methods = mutableSetOf<MethodSpec>()

    // NameAllocator to create valid method names
    val nameAllocator = NameAllocator()

    // Some other boilerplate we'll need
    val t = TypeVariableName.get("T")
    val returnType = ParameterizedTypeName.get(ClassName.get(JsonAdapter::class.java), t)

    // A utility createTypeAdapter method for methods to use and not worry about reflection stuff
    val jsonAdapterCreator = MethodSpec.methodBuilder("createJsonAdapter")
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .addTypeVariable(t)
        .returns(returnType)
        .addParameter(String::class.java, "modelName")
        .addParameter(String::class.java, "methodName")
        .addParameter(ArrayTypeName.of(Object::class.java), "args")
        .varargs()
        .beginControlFlow("try")
        // If we have args, we need to create a Class[] to give the getMethod() call to properly resolve
        .beginControlFlow("if (args != null && args.length > 0)")
        .addStatement("\$1T[] params = new \$1T[args.length]", Class::class.java)
        .beginControlFlow("for (int i = 0; i < args.length; i++)")
        .addStatement("params[i] = args[i].getClass()")
        .endControlFlow()
        .addStatement("\treturn (\$T) \$T.forName(modelName)" +
            ".getMethod(methodName, params).invoke(null, args)",
            returnType,
            Class::class.java)
        .nextControlFlow("else")
        .addStatement("\treturn (\$T) \$T.forName(modelName).getMethod(methodName).invoke(null)",
            returnType,
            Class::class.java)
        .endControlFlow()
        .nextControlFlow("catch (\$T e)",
            Exception::class.java) // Can't use ReflectiveOperationException till API 19
        .addStatement("throw new \$T(\$S, e)", RuntimeException::class.java,
            "Cortex reflective jsonAdapter " +
                "invocation failed.")
        .endControlFlow()
        .build()

    val factoryCreator = MethodSpec.methodBuilder("getJsonAdapterFactory")
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .addTypeVariable(t)
        .returns(JsonAdapter.Factory::class.java)
        .addParameter(String::class.java, "modelName")
        .addParameter(String::class.java, "methodName")
        .beginControlFlow("try")
        .addStatement("\treturn (\$T) \$T.forName(modelName).getMethod(methodName).invoke(null)",
            JsonAdapter.Factory::class.java,
            Class::class.java)
        .nextControlFlow("catch (\$T e)",
            Exception::class.java) // Can't use ReflectiveOperationException till API 19
        .addStatement("throw new \$T(\$S, e)", RuntimeException::class.java, "Cortex reflective " +
            "jsonAdapterFactory invocation failed.")
        .endControlFlow()
        .build()

    val nameResolver = MethodSpec.methodBuilder("resolveNameMoshiSupport")
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .returns(String::class.java)
        .addParameter(String::class.java, "simpleName")
        .beginControlFlow("if (simpleName.startsWith(\$S))", AV_PREFIX)
        .addStatement("return simpleName.substring(\$S.length())", AV_PREFIX)
        .nextControlFlow("else")
        .addStatement("return simpleName")
        .endControlFlow()
        .build()

    val create = MethodSpec.methodBuilder("create")
        .addModifiers(PUBLIC)
        .addAnnotation(Override::class.java)
        .addParameters(ImmutableSet.of(TYPE_SPEC, ANNOTATIONS_SPEC, MOSHI_SPEC))
        .returns(FACTORY_RETURN_TYPE_NAME)
        /*
         * First we want to pull out the package and simple names
         * The idea here is that we'll split on package names initially and then split on simple names in each
         * package-specific method's switch statements.
         *
         * This only covers the model name and autovalue name, but maybe some day we can make this more flexible
         * by going up and finding the first neuron-annotated class as the root if we need to.
         *
         * Note that we only get the package name first. If we get a match, then we snag the simple name and
         * possibly strip the AutoValue_ prefix if necessary.
         */
        .addComment("Avoid providing an adapter for an annotated type.")
        .addStatement("if (!\$N.isEmpty()) return null", ANNOTATIONS_SPEC)
        .addStatement("\$T<?> rawType = \$T.getRawType(\$N)",
            Class::class.java,
            TypeName.get(MoshiTypes::class.java),
            TYPE_SPEC)
        .addStatement("if (rawType.isPrimitive()) return null")
        .addStatement("if (!rawType.isAnnotationPresent(\$T.class)) return null",
            Neuron::class.java)
        .addStatement("String packageName = rawType.getPackage().getName()")
    // Begin the switch
    create.beginControlFlow("switch (packageName)", TYPE_SPEC)
    modelsByPackage.forEach { packageName, entries ->
      // Create the package-specific method
      val packageCreatorMethod = MethodSpec.methodBuilder(
          nameAllocator.newName("${packageName}JsonAdapter"))
          .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
          .addTypeVariable(t)
          .returns(returnType)
          .addParameter(String::class.java, "name")
          .addParameters(ImmutableSet.of(
              TYPE_SPEC, ANNOTATIONS_SPEC, MOSHI_SPEC))
          .addCode(createPackageSwitch(packageName, entries, returnType))
          .build()

      // Switch on the package name and return the result from the corresponding method
      create.addCode("case \$S:\n", packageName)
      create.addStatement("\treturn \$N(\$N(rawType.getSimpleName()), \$N, \$N, \$N)",
          packageCreatorMethod,
          nameResolver,
          TYPE_SPEC,
          ANNOTATIONS_SPEC,
          MOSHI_SPEC)
      methods += packageCreatorMethod
    }

    // Default is always to return null in adapters
    create.addCode("default:\n")
        .addStatement("return null")
        .endControlFlow()

    methods += nameResolver
    methods += factoryCreator
    methods += jsonAdapterCreator
    methods += create.build()

    return methods
  }

  /**
   * Creates a package-specific `switch` implementation that switches on a simple model name.
   *
   * @param packageName the package name for this model data
   * @param data the model data for this package
   * @param returnType the return type reference to if necessary
   */
  private fun createPackageSwitch(
      packageName: String,
      data: Map<String, MoshiSupportMeta>,
      returnType: TypeName): CodeBlock {
    val code = CodeBlock.builder()
    code.beginControlFlow("switch (name)")
    data.forEach { modelName, (methodName, argCount, isFactory) ->
      code.add("case \$S:\n", modelName)
      code.add(CodeBlock.builder()
          .apply {
            if (isFactory) {
              addStatement("\treturn (\$T) getJsonAdapterFactory(\$S, \$S).create(\$N, \$N, \$N)",
                  returnType,
                  "$packageName.$modelName",
                  methodName,
                  TYPE_SPEC,
                  ANNOTATIONS_SPEC,
                  MOSHI_SPEC)
            } else {
              add("\treturn createJsonAdapter(\$S, \$S",
                  "$packageName.$modelName", methodName)
                  .apply {
                    if (argCount == 1) {
                      // These need a moshi instance to defer to for other type adapters
                      add(", \$N", MOSHI_SPEC)
                    }
                  }
              add(");\n")
            }
          }
          .build())
    }
    code.add("default:")
        .addStatement("\nreturn null")
        .endControlFlow()
    return code.build()
  }

  private fun addControlFlowGeneric(
      block: CodeBlock.Builder, elementTypeName: TypeName,
      element: Element, numGenerics: Int) {
    getJsonAdapterMethod(element)?.run {
      val typeName = (elementTypeName as ParameterizedTypeName).rawType
      val typeBlock = CodeBlock.of("rawType")
      addControlFlow(block, typeBlock, typeName, numGenerics)

      val returnStatement = "return \$L.\$L(\$N, ((\$T) \$N).getActualTypeArguments())"

      val paramsCount = parameters.size
      if (paramsCount > 1) {
        block.addStatement(returnStatement,
            element.simpleName,
            simpleName,
            MOSHI_SPEC,
            ParameterizedType::class.java,
            TYPE_SPEC)
      }
    }
  }

  private fun addControlFlow(
      block: CodeBlock.Builder,
      typeBlock: CodeBlock,
      elementTypeName: TypeName,
      pos: Int) {
    when (pos) {
      0 -> block.beginControlFlow("if (\$L.equals(\$T.class))", typeBlock, elementTypeName)
      else -> block.nextControlFlow("else if (\$L.equals(\$T.class))", typeBlock, elementTypeName)
    }
  }

  private fun getJsonAdapterMethod(element: Element): ExecutableElement? {
    val jsonAdapterType = ParameterizedTypeName.get(
        ClassName.get(JsonAdapter::class.java), TypeName.get(element.asType()))
    return ElementFilter.methodsIn(element.enclosedElements)
        .filter { it.modifiers.containsAll(setOf(Modifier.STATIC, PUBLIC)) }
        .find { TypeName.get(it.returnType) == jsonAdapterType }
  }

  private fun getJsonAdapterFactoryMethod(element: Element): ExecutableElement? {
    return ElementFilter.methodsIn(element.enclosedElements)
        .filter { it.modifiers.containsAll(setOf(Modifier.STATIC, PUBLIC)) }
        .find { TypeName.get(it.returnType) == FACTORY_CLASS_NAME }
  }
}

class MoshiSupportMetaAdapter : JsonAdapter<MoshiSupportMeta>() {

  companion object {
    private val NAMES = arrayOf("methodName", "argCount", "isFactory")
    private val OPTIONS = JsonReader.Options.of(*NAMES)

    val FACTORY = Factory { type, _, _ ->
      when (type) {
        MoshiSupportMeta::class.java -> MoshiSupportMetaAdapter()
        else -> null
      }
    }
  }

  override fun fromJson(reader: JsonReader): MoshiSupportMeta {
    var methodName by Delegates.notNull<String>()
    var argCount = 0
    var isFactory = false
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.selectName(OPTIONS)) {
        0 -> methodName = reader.nextString()
        1 -> argCount = reader.nextInt()
        2 -> isFactory = reader.nextBoolean()
        else -> throw IllegalArgumentException("Unrecognized name: ${reader.nextName()}")
      }
    }
    reader.endObject()
    return MoshiSupportMeta(methodName, argCount, isFactory)
  }

  override fun toJson(writer: com.squareup.moshi.JsonWriter, model: MoshiSupportMeta?) {
    model?.run {
      writer.beginObject()
          .name("methodName")
          .value(methodName)
          .name("argCount")
          .value(argCount)
          .name("isFactory")
          .value(isFactory)
      writer.endObject()
    }
  }
}

data class MoshiSupportMeta(val methodName: String, val argCount: Int = 0,
    val isFactory: Boolean = false)