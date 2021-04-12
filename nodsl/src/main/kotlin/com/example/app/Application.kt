package com.example.app

import com.example.annotation.HowManyControllers
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.example.app.nodsl"])
class Application

@HowManyControllers(1000)
fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
