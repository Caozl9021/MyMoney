package com.example.billkit.spec

import java.io.File

/** JVM-only release gate using exactly the Android production parser and Rhino engine. */
object RulePackageCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: validateRulePackage -PrulePackage=/path/to/package.zip" }
        val pack = File(args[0]).inputStream().use { PortableRulePackage.load(it) }
        val count = pack.validate()
        println("VALID ${pack.version}: ${pack.bundle.rules.size} rules, $count samples, SHA256 ${pack.digest}")
    }
}
