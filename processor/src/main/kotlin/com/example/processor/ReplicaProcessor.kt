package com.example.processor

import com.example.annotation.HowManyControllers
import com.example.annotation.HowManyRoutes
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.plusParameter
import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.io.File
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Table

@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_11)
@SupportedAnnotationTypes(value = ["com.example.annotation.HowManyControllers", "com.example.annotation.HowManyRoutes"])
class ReplicaProcessor: AbstractProcessor() {

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val kaptKotlinGeneratedDir = processingEnv.options["kapt.kotlin.generated"] ?: return false
        logInfo("processing KAPT")
        processControllers(roundEnv, kaptKotlinGeneratedDir, HowManyControllers::class.java)
        processRoutes(roundEnv, kaptKotlinGeneratedDir, HowManyRoutes::class.java)

        return true
    }

    private fun processRoutes(roundEnv: RoundEnvironment, outputDir: String, annotation: Class<HowManyRoutes>) {
        processAnnotation(roundEnv, outputDir, annotation){ element ->
            val replica = element.getAnnotation(annotation)
            (1..replica.value).flatMap { index ->
                listOf(
                    createData(index),
                    createInterfaceRepositories(index),
                    createConcreteRepositories(index),
                    createHandler(index),
                    createFnRouter(index),
                    createFnBeans(index)
                )
            } + listOf(FunSpec.builder("allBeans")
                .returns(BeanDefinitionDsl::class)
                .addStatement(""" return beans {
                    ${(1..replica.value).joinToString("\n") {
                        """
                            beans${it}(this)
                        """.trimIndent()
                    }}  
                }
                """.trimIndent())
                .build()
                .run { createFile(null, "allBeans", "dsl", this,
                    listOf("org.springframework.context.support.beans") +
                        (1..replica.value).map { "${packageFn(it, "dsl")}.beans$it" }
                ) })
        }
    }

    private fun createFnRouter(index: Int): FileSpec {
        val packageName = packageFn(index,"dsl")
        val className = "helloRouter${index}"
        val handlerName = "HelloHandler${index}"
        val classType = FunSpec.builder(className)
            .addParameter(ParameterSpec
                .builder("handler", ClassName(packageName, handlerName))
                .build())
            .returns(RouterFunction::class.plusParameter(ServerResponse::class))
            .addStatement("""
                return router {
                    "hello${index}".nest {
                        GET("", handler::hello)
                        accept(APPLICATION_JSON).nest {
                            POST("", handler::save)
                        }
                    }
                }
            """.trimIndent())
            .build()

        return createFile(index, className, "dsl", classType,
            listOf(
                "org.springframework.web.servlet.function.router",
                "org.springframework.web.servlet.function.ServerRequest",
                "org.springframework.http.MediaType.APPLICATION_JSON"
            )
        )
    }

    private fun createFnBeans(index: Int): FileSpec {
        val packageName = packageFn(index,"dsl")
        val className = "beans${index}"
        val handlerName = "${packageName}.HelloHandler${index}"
        val repositoryName = "${packageName}.InMemoryCustomerRepository${index}"
        val routerName = "helloRouter${index}"
        val classType = FunSpec.builder(className)
            .addParameter("dsl", BeanDefinitionDsl::class)
            .addStatement("""                
                dsl.bean<$handlerName>()
                dsl.bean<$repositoryName>()
                dsl.bean(::$routerName)
            """.trimIndent())
            .build()

        return createFile(index, className, "dsl", classType,
            listOf(
                "org.springframework.context.support.beans",
                "${packageName}.$routerName"
            )
        )
    }

    private fun createHandler(index: Int): FileSpec {
        val packageName = packageFn(index,"dsl")
        val className = "HelloHandler${index}"
        val repositoryName = "CustomerRepository${index}"
        val classType = TypeSpec.classBuilder(className)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("repo", ClassName(packageName, repositoryName))
                .build())
            .addProperty(PropertySpec
                .builder("repo", ClassName(packageName, repositoryName))
                .initializer("repo")
                .build())
            .addFunction(FunSpec.builder("hello")
                .addParameter("request", ServerRequest::class)
                .returns(ServerResponse::class)
                .addStatement("""
                    return ServerResponse.ok().body("dsl ${index}")
                """.trimIndent())
                .build())
            .addFunction(FunSpec.builder("save")
                .addParameter("request", ServerRequest::class)
                .returns(ServerResponse::class)
                .addStatement("""
                    return ServerResponse.ok().build()
                """.trimIndent())
                .build())
            .build()

        return createFile(index, className, "dsl", classType)
    }

    private fun createData(index: Int): FileSpec {
        val className = "Customer${index}"
        val classType = TypeSpec.classBuilder(className)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("id", ClassName.bestGuess("kotlin.Long"))
                .addParameter("name", ClassName.bestGuess("kotlin.String"))
                .build())
            .addProperty(PropertySpec
                .builder("id", ClassName.bestGuess("kotlin.Long"))
                .initializer("id")
                .build())
            .addProperty(PropertySpec
                .builder("name", ClassName.bestGuess("kotlin.String"))
                .initializer("name")
                .build())
            .build()

        return createFile(index, className, "dsl", classType)
    }

    private fun createInterfaceRepositories(index: Int): FileSpec {
        val packageName = packageFn(index,"dsl")
        val className = "CustomerRepository${index}"
        val customerClass = "Customer${index}"
        val classType = TypeSpec.interfaceBuilder(className)
            .addFunction(FunSpec.builder("findAll")
                .addModifiers(KModifier.ABSTRACT)
                .returns(ClassName.bestGuess(List::class.qualifiedName!!)
                    .plusParameter(ClassName(packageName, customerClass)))
                .build())
            .addFunction(FunSpec.builder("save")
                .addModifiers(KModifier.ABSTRACT)
                .addParameter("customer", ClassName(packageName, customerClass))
                .build())
            .build()

        return createFile(index, className, "dsl", classType)
    }

    private fun createConcreteRepositories(index: Int): FileSpec {
        val packageName = packageFn(index,"dsl")
        val className = "InMemoryCustomerRepository${index}"
        val interfaceName = "CustomerRepository${index}"
        val customerClass = "Customer${index}"
        val classType = TypeSpec.classBuilder(className)
            .addSuperinterface(ClassName(packageName, interfaceName))
            .addFunction(FunSpec.builder("findAll")
                .addModifiers(KModifier.OVERRIDE)
                .returns(ClassName.bestGuess(List::class.qualifiedName!!)
                    .plusParameter(ClassName(packageName, customerClass)))
                .addStatement("""return listOf(${packageName}.Customer${index}(${index}, "hello dsl ${index}"))""")
                .build())
            .addFunction(FunSpec.builder("save")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("customer", ClassName(packageName, customerClass))
                .build())
            .build()

        return createFile(index, className, "dsl", classType)
    }

    private fun processControllers(roundEnv: RoundEnvironment, outputDir: String, annotation: Class<HowManyControllers>) {
        processAnnotation(roundEnv, outputDir, annotation){ element ->
            val replica = element.getAnnotation(annotation)
            (1..replica.value).flatMap { index ->
                listOf(
                    createEntity(index),
                    createRepositories(index),
                    createServices(index),
                    createControllers(index)
                )
            }
        }
    }

    private fun <T: Annotation> processAnnotation(
        roundEnv: RoundEnvironment,
        outputDir: String,
        annotation: Class<T>,
        body: (Element) -> List<FileSpec>
    ) {
        roundEnv.getElementsAnnotatedWith(annotation)
            .mapNotNull(body)
            .forEach { fileSpec ->
                fileSpec.forEach { it.writeTo(File(outputDir)) }
            }
    }

    private fun createEntity(index: Int): FileSpec{
        val className = "HelloEntity${index}"
        val type: TypeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(Entity::class)
            .addAnnotation(AnnotationSpec.builder(Table::class)
                .addMember("""name="Hello", schema="Hello"""")
                .build())
            .addProperty(PropertySpec.builder("id", Long::class).mutable().initializer("0")
                .addAnnotation(Id::class)
                .addAnnotation(Column::class)
                .build())
            .addProperty(PropertySpec.builder("name", String::class, KModifier.LATEINIT).mutable()
                .addAnnotation(Column::class)
                .build())
            .build()


        return createFile(index, className, "nodsl", type)
    }

    private fun createRepositories(index: Int): FileSpec {

        val className = "HelloRepository${index}"
        val type = TypeSpec
            .interfaceBuilder(className)
            .addSuperinterface(ClassName.bestGuess(CrudRepository::class.qualifiedName!!)
                .plusParameter(ClassName.bestGuess("${packageFn(index,"nodsl")}.HelloEntity${index}"))
                .plusParameter(ClassName("kotlin", "Long")))
            .addAnnotation(Repository::class)
            .build()

        return createFile(index, className, "nodsl", type)
    }

    private fun packageFn(index: Int?, prefix: String) = "com.example.app.${prefix}.auto${index ?: ""}.autogenerated"

    private fun createControllers(index: Int): FileSpec {
        val className = "HelloController${index}"
        val type = TypeSpec
            .classBuilder(className)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("service", ClassName(packageFn(index,"nodsl"), "HelloService${index}"))
                .build())
            .addProperty(PropertySpec
                .builder("service", ClassName(packageFn(index,"nodsl"), "HelloService${index}"))
                .initializer("service")
                .build())
            .addAnnotation(RestController::class)
            .addFunction(
                FunSpec.builder("hello")
                    .returns(String::class)
                    .addAnnotation(
                        AnnotationSpec.builder(GetMapping::class)
                            .addMember("""value=["hello${index}"]""")
                            .build())
                    .addStatement(
                        """
                            return service.hello()
                        """.trimIndent())
                    .build()
            )
            .build()

        return createFile(index, className, "nodsl", type)
    }

    private fun createServices(index: Int): FileSpec {
        val className = "HelloService${index}"
        val type = TypeSpec
            .classBuilder(className)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("repo", ClassName(packageFn(index,"nodsl"), "HelloRepository${index}"))
                .build())
            .addProperty(PropertySpec
                .builder("repo", ClassName(packageFn(index,"nodsl"), "HelloRepository${index}"))
                .initializer("repo")
                .build())
            .addAnnotation(Service::class)
            .addFunction(
                FunSpec.builder("hello")
                    .returns(String::class)
                    .addStatement(
                        """
                            return "hello ${index}"
                        """.trimIndent())
                    .build()
            )
            .build()

        return createFile(index, className, "nodsl", type)
    }

    private fun createFile(index: Int, className: String, prefix: String, type: TypeSpec, imports: List<String> = emptyList()): FileSpec {
        logInfo("$type")

        return FileSpec.builder(packageFn(index, prefix), className)
            .apply { imports.forEach{
                addImport(className(it))
            } }
            .addType(type)
            .build()
    }

    private fun createFile(index: Int?, className: String, prefix: String, type: FunSpec, imports: List<String> = emptyList()): FileSpec {
        logInfo("$type")

        return FileSpec.builder(packageFn(index, prefix), className)
            .apply { imports.forEach{
                val packageClassName = className(it)
                addImport(packageClassName.packageName, packageClassName.simpleName)
            } }
            .addFunction(type)
            .build()
    }

    private fun className(it: String): ClassName {
        val (packageName, className) = it.split(".").run {
            dropLast(1).joinToString(".") to last()
        }
        return ClassName(packageName, className)
    }

    private fun logInfo(message: String) {
//        processingEnv.messager.printMessage(Diagnostic.Kind.NOTE, message)
    }
}