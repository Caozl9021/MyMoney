package com.example.billkit.spec

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

/** No Java bridge; instruction and elapsed budgets apply to compilation and evaluation. */
internal object RuleSandbox {
    class BudgetExceeded : Error("规则执行超过预算")
    private val deadline = ThreadLocal<Long>()
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 10_000
            setClassShutter { false }
            maximumInterpreterStackDepth = 128
        }
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.nanoTime() > (deadline.get() ?: 0L)) throw BudgetExceeded()
        }
    }
    fun enter(): Context {
        deadline.set(System.nanoTime() + 1_000_000_000L)
        return factory.enterContext()
    }
    fun exit() { Context.exit(); deadline.remove() }
}
