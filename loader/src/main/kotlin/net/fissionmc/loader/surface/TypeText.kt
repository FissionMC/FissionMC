package net.fissionmc.loader.surface

/**
 * Parses Java source type text (fully qualified, nested classes written with `$`) into JVM
 * descriptors and generic signatures. Grammar covers what surface specs need: class types with
 * type arguments, wildcards, arrays, varargs, primitives and type variables.
 */
sealed interface JType {
    fun descriptor(vars: Map<String, String>): String
    fun signature(): String
    val generic: Boolean

    data class Prim(val name: String) : JType {
        override fun descriptor(vars: Map<String, String>) = PRIMS.getValue(name)
        override fun signature() = PRIMS.getValue(name)
        override val generic get() = false
    }

    data class Cls(val fqn: String, val args: List<JType>) : JType {
        val internal get() = fqn.replace('.', '/')
        override fun descriptor(vars: Map<String, String>) = "L$internal;"
        override fun signature() =
            if (args.isEmpty()) "L$internal;" else "L$internal<" + args.joinToString("") { it.signature() } + ">;"
        override val generic get() = args.isNotEmpty()
    }

    data class Var(val name: String) : JType {
        override fun descriptor(vars: Map<String, String>) = "L" + (vars[name] ?: "java/lang/Object") + ";"
        override fun signature() = "T$name;"
        override val generic get() = true
    }

    data class Arr(val of: JType) : JType {
        override fun descriptor(vars: Map<String, String>) = "[" + of.descriptor(vars)
        override fun signature() = "[" + of.signature()
        override val generic get() = of.generic
    }

    data class Wild(val bound: JType?, val upper: Boolean) : JType {
        override fun descriptor(vars: Map<String, String>) = error("wildcard outside type arguments")
        override fun signature() = when {
            bound == null -> "*"
            upper -> "+" + bound.signature()
            else -> "-" + bound.signature()
        }
        override val generic get() = true
    }

    companion object {
        val PRIMS = mapOf(
            "void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S",
            "int" to "I", "long" to "J", "float" to "F", "double" to "D",
        )
    }
}

/** A type parameter declaration such as `T extends java.lang.Comparable<T>`. */
data class TypeParam(val name: String, val bounds: List<JType>) {
    fun signature(): String {
        if (bounds.isEmpty()) return "$name:Ljava/lang/Object;"
        val first = bounds.first()
        // Interface bounds are written with a double colon; we cannot know, so classes are assumed
        // unless the first bound is java.lang.Object. javac does not care for compile-only stubs.
        return name + ":" + bounds.joinToString(":") { it.signature() }
    }
}

class TypeParser(private val src: String, private val typeVars: Set<String>) {
    private var pos = 0

    fun parseType(): JType {
        skipWs()
        if (peek() == '?') {
            pos++
            skipWs()
            return when {
                word("extends") -> JType.Wild(parseType(), true)
                word("super") -> JType.Wild(parseType(), false)
                else -> JType.Wild(null, true)
            }
        }
        val name = ident()
        var t: JType = when {
            name in JType.PRIMS -> JType.Prim(name)
            name in typeVars -> JType.Var(name)
            '.' !in name -> error("type '$name' must be fully qualified (in: $src)")
            else -> {
                skipWs()
                val args = mutableListOf<JType>()
                if (peek() == '<') {
                    pos++
                    while (true) {
                        args += parseType()
                        skipWs()
                        when (next()) {
                            ',' -> continue
                            '>' -> break
                            else -> error("bad type arguments in: $src")
                        }
                    }
                }
                JType.Cls(name, args)
            }
        }
        while (true) {
            skipWs()
            if (src.startsWith("[]", pos)) { pos += 2; t = JType.Arr(t) } else break
        }
        return t
    }

    fun parseTypeParams(): List<TypeParam> {
        skipWs()
        if (peek() != '<') return emptyList()
        pos++
        val out = mutableListOf<TypeParam>()
        while (true) {
            val name = ident()
            skipWs()
            val bounds = mutableListOf<JType>()
            if (word("extends")) {
                bounds += parseType()
                skipWs()
                while (peek() == '&') { pos++; bounds += parseType(); skipWs() }
            }
            out += TypeParam(name, bounds)
            skipWs()
            when (next()) {
                ',' -> continue
                '>' -> return out
                else -> error("bad type parameters in: $src")
            }
        }
    }

    fun ident(): String {
        skipWs()
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] in "_$.")) pos++
        if (start == pos) error("expected identifier at $pos in: $src")
        return src.substring(start, pos)
    }

    fun word(w: String): Boolean {
        skipWs()
        if (src.startsWith(w, pos) && (pos + w.length == src.length || !src[pos + w.length].isLetterOrDigit())) {
            pos += w.length
            return true
        }
        return false
    }

    fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'
    fun next(): Char = src[pos++]
    fun skipWs() { while (pos < src.length && src[pos].isWhitespace()) pos++ }
    fun atEnd(): Boolean { skipWs(); return pos >= src.length }
    fun rest(): String = src.substring(pos)
    fun expect(c: Char) { skipWs(); if (next() != c) error("expected '$c' at ${pos - 1} in: $src") }
}
