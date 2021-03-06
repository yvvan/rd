package com.jetbrains.rd.cross.util

import com.jetbrains.rd.util.ILoggerFactory
import com.jetbrains.rd.util.LogLevel
import com.jetbrains.rd.util.Logger

/**
 * Combines loggers, so it logs output to each provided argument if necessary.
 */
class CombinatorLogger(private val loggers: List<Logger>) : Logger {
    override fun log(level: LogLevel, message: Any?, throwable: Throwable?) =
        loggers.forEach { logger ->
            if (logger.isEnabled(level)) {
                logger.log(level, message, throwable)
            }
        }

    override fun isEnabled(level: LogLevel) = loggers.any { it.isEnabled(level) }
}

/**
 * Creates CombinatorLogger from loggers of factories.
 */
class CombinatorLoggerFactory(private val factories: List<ILoggerFactory>) : ILoggerFactory {
    override fun getLogger(category: String) = CombinatorLogger(factories.map { it.getLogger(category) })
}