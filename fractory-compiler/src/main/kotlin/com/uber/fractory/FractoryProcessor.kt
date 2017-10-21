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

package com.uber.fractory

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.uber.fractory.annotations.Cortex
import com.uber.fractory.annotations.Fractory
import com.uber.fractory.annotations.FractoryNode
import com.uber.fractory.extensions.GsonSupport
import com.uber.fractory.extensions.MoshiSupport
import com.uber.fractory.packaging.GenerationalClassUtil
import java.io.IOException
import java.util.ArrayList
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.WARNING
import kotlin.properties.Delegates

typealias ExtensionName = String
typealias ExtensionArgs = Map<String, String>
typealias ExtensionArgsInput = MutableMap<String, String>
typealias MoshiTypes = com.squareup.moshi.Types

/**
 * Generates a Fractory that adapts all [FractoryNode] annotated types.

 * Supports Gson and Moshi.
 */
open class FractoryProcessor : AbstractProcessor() {

  companion object {
    private const val FRACTORY_PREFIX = "Fractory_"
    private const val CORTEX_PREFIX = "Cortex_"
  }

  private val fractoryAdapter = Moshi.Builder()
      .add(FractoryAdapter.FACTORY)
      .build()
      .adapter(FractoryModel::class.java)

  // A little meh, but we can build out an actual plugin system in the future if we need to.
  // This just ensures all the code below is extension-agnostic.
  private val extensionElements = mapOf(
      Pair(GsonSupport(), mutableListOf<Element>()),
      Pair(MoshiSupport(), mutableListOf<Element>())
  )

  private lateinit var typeUtils: Types
  private lateinit var elementUtils: Elements

  override fun getSupportedAnnotationTypes(): Set<String> {
    // Food for thought - Optionally also look for AutoValue classes?
    return listOf(FractoryNode::class, Fractory::class, Cortex::class)
        .map { it.java.name }
        .toSet()
  }

  override fun getSupportedSourceVersion(): SourceVersion {
    return SourceVersion.latestSupported()
  }

  @Synchronized override fun init(processingEnv: ProcessingEnvironment) {
    super.init(processingEnv)
    typeUtils = processingEnv.typeUtils
    elementUtils = processingEnv.elementUtils
  }

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    processFractory(roundEnv)
    processCortex(roundEnv)

    // return true, we're the only ones that care about these annotations.
    return true
  }

  private fun processFractory(roundEnv: RoundEnvironment) {
    val neuronElements = roundEnv.findElementsAnnotatedWith<FractoryNode>()
    neuronElements
        .forEach { element ->
          extensionElements.forEach { extension, elements ->
            if (extension.isApplicable(processingEnv, element as TypeElement)) {
              elements.add(element)
            }
          }
        }

    val adaptorFactories = roundEnv.findElementsAnnotatedWith<Fractory>()
    adaptorFactories
        .cast<TypeElement>()
        .onEach { checkAbstract(it) }
        .forEach { factory ->
          val applicableExtensions = extensionElements
              .filter { (extension, _) ->
                extension.isTypeSupported(elementUtils, typeUtils, factory)
              }

          if (applicableExtensions.isEmpty()) {
            error(factory, """
              |No extensions applicable for the given @Fractory-annotated element
              |Detected factories: [${adaptorFactories.joinToString { it.toString() }}]
              |Available extensions: [${extensionElements.keys.joinToString()}]
              |Detected models: [${neuronElements.joinToString()}]
              """.trimMargin())
            return@forEach
          } else if (applicableExtensions
              .none { (_, elements) -> elements.isNotEmpty() }) {
            error(factory, """
              |No @Neuron-annotated elements applicable for the given @Fractory-annotated element with the current fractory extensions
              |Detected factories: [${adaptorFactories.joinToString { it.toString() }}]
              |Available extensions: [${extensionElements.keys.joinToString()}]
              |Detected models: [${neuronElements.joinToString()}]
              """.trimMargin())
            return@forEach
          }

          val implementationMethods = ArrayList<MethodSpec>()
          val globalExtras = mutableMapOf<ExtensionName, ExtensionArgsInput>()
          applicableExtensions.forEach { extension, elements ->
            val extras: ExtensionArgsInput = mutableMapOf()
            implementationMethods.add(
                extension.createFractoryImplementationMethod(elements, extras))
            globalExtras.put(extension.javaClass.name, extras)
          }
          if (!implementationMethods.isEmpty()) {
            val adapterName = factory.classNameOf()
            val packageName = factory.packageName()
            val factorySpec = TypeSpec.classBuilder(
                ClassName.get(packageName, FRACTORY_PREFIX + adapterName))
                .addModifiers(FINAL)
                .superclass(ClassName.get(packageName, adapterName))
                .addMethods(implementationMethods)
                .build()
            val file = JavaFile.builder(packageName, factorySpec).build()
            file.writeToFiler()?.run {
              // Write metadata to resources for cortexes to pick up
              val fractoryModel = FractoryModel("$packageName.$adapterName", globalExtras)
              val json = fractoryAdapter.toJson(fractoryModel)
              GenerationalClassUtil.writeIntermediateFile(processingEnv,
                  packageName,
                  adapterName + GenerationalClassUtil.ExtensionFilter.FRACTORY.extension,
                  json)
            }
          }
        }
  }

  private fun processCortex(roundEnv: RoundEnvironment) {
    val cortexes = roundEnv.findElementsAnnotatedWith<Cortex>()
    if (cortexes.isEmpty()) {
      return
    }

    // Load the fractories from the classpath
    val fractoryJsons = GenerationalClassUtil.loadObjects<String>(
        GenerationalClassUtil.ExtensionFilter.FRACTORY,
        processingEnv)

    if (fractoryJsons.isEmpty()) {
      message(WARNING, cortexes.iterator().next(), "No fractories found on the classpath.")
      return
    }

    val fractories = fractoryJsons.map { fractoryAdapter.fromJson(it)!! }
    val extrasByExtension = mutableMapOf<ExtensionName, MutableSet<ExtensionArgs>>()
    fractories.map { it.extras }
        .flatMap { it.entries }
        .forEach {
          extrasByExtension.getOrPut(it.key, { mutableSetOf() }).add(it.value)
        }

    // Iterate through the cortexes to generate their implementations. Currently these will all be the same
    // but in the future we could add configuration to cortex.
    cortexes.cast<TypeElement>()
        .onEach { checkAbstract(it) }
        .forEach { factory ->
          val implementationMethods = ArrayList<MethodSpec>()
          extensionElements.forEach { extension, _ ->
            if (extension.isTypeSupported(elementUtils, typeUtils, factory)) {
              val extras = extrasByExtension[extension.javaClass.name] ?: setOf<ExtensionArgs>()
              implementationMethods.addAll(extension.createCortexImplementationMethod(extras))
            }
          }

          if (!implementationMethods.isEmpty()) {
            val adapterName = factory.classNameOf()
            val packageName = factory.packageName()
            val factorySpec = TypeSpec.classBuilder(
                ClassName.get(packageName, CORTEX_PREFIX + adapterName))
                .addModifiers(PUBLIC, FINAL)
                .superclass(ClassName.get(packageName, adapterName))
                .addMethods(implementationMethods)

            JavaFile.builder(packageName, factorySpec.build()).build()
                .writeToFiler()
          } else {
            error(factory,
                "Must implement a supported interface! TypeAdapterFactory, JsonAdapter, etc.")
          }
        }
  }

  /**
   * Writes a file to a filer or reports an error if it fails.
   *
   * @return true if successful, false if not.
   */
  private fun JavaFile.writeToFiler(): Unit? {
    return try {
      writeTo(processingEnv.filer)
    } catch (e: IOException) {
      error("Failed to write Fractory: " + e.localizedMessage)
      null
    }
  }

  /**
   * Checks if an element is abstract or not and errors if not.
   *
   * @param element element to check
   * @return true if abstract, false if not.
   */
  private fun checkAbstract(element: TypeElement) {
    if (ABSTRACT !in element.modifiers) {
      error(element, "Must be abstract!")
    }
  }

  private fun error(message: String, vararg args: Any) {
    error(null, message, *args)
  }

  private fun error(element: Element?, message: String, vararg args: Any) {
    message(ERROR, element, message, *args)
  }

  private fun message(kind: Diagnostic.Kind, element: Element?, message: String, vararg args: Any) {
    var localMessage = message
    if (args.isNotEmpty()) {
      localMessage = String.format(message, *args)
    }

    if (element == null) {
      processingEnv.messager.printMessage(kind, localMessage)
    } else {
      processingEnv.messager.printMessage(kind, localMessage, element)
    }
  }
}

class FractoryAdapter(moshi: Moshi) : JsonAdapter<FractoryModel>() {

  companion object {
    private val NAMES = arrayOf("name", "extras")
    private val OPTIONS = JsonReader.Options.of(*NAMES)

    val FACTORY = Factory { type, _, moshi ->
      when (type) {
        FractoryModel::class.java -> FractoryAdapter(moshi)
        else -> null
      }
    }
  }

  private val extrasAdapter = moshi.adapter<Map<ExtensionName, ExtensionArgs>>(
      MoshiTypes.newParameterizedType(
          Map::class.java,
          String::class.java,
          MoshiTypes.newParameterizedType(Map::class.java,
              String::class.java,
              String::class.java)))

  override fun fromJson(reader: JsonReader): FractoryModel {
    var name by Delegates.notNull<ExtensionName>()
    var extras by Delegates.notNull<Map<ExtensionName, ExtensionArgs>>()
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.selectName(OPTIONS)) {
        0 -> name = reader.nextString()
        1 -> extras = extrasAdapter.fromJson(reader)!!
        else -> throw IllegalArgumentException("Unrecognized name: ${reader.nextName()}")
      }
    }
    reader.endObject()
    return FractoryModel(name, extras)
  }

  override fun toJson(writer: com.squareup.moshi.JsonWriter, model: FractoryModel?) {
    model?.run {
      writer.beginObject()
          .name("name")
          .value(name)
          .name("extras")
      extrasAdapter.toJson(writer, extras)
      writer.endObject()
    }
  }
}

/** Return a list of elements annotated with [T]. */
inline fun <reified T : Annotation> RoundEnvironment.findElementsAnnotatedWith(): Set<Element>
    = getElementsAnnotatedWith(T::class.java)

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <T> Iterable<*>.cast() = map { it as T }

data class FractoryModel(
    val name: String,
    val extras: Map<ExtensionName, ExtensionArgs>)

fun String.asPackageAndName(): Pair<String, String> {
  val lastIndex = lastIndexOf(".")
  val modelPackage = substring(0, lastIndex)
  val modelSimpleName = substring(lastIndex + 1)
  return Pair(modelPackage, modelSimpleName)
}