package net.fissionmc.loader.surface

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File

/**
 * The FissionMC "surface": the members Paper adds to or exposes on vanilla classes.
 *
 * Sources (a directory):
 *  - `*.at`            Paper access-transformer syntax (`public-f owner member(desc)`), e.g. Paper's own paper.at.
 *  - `members/` `*.txt` lines `owner: <java declaration>` (FQN types, `$` for nested), or `owner: implements Iface`.
 *
 * Compile time ([addMembers]) adds stub declarations so ported Paper code compiles against vanilla.
 * Run time ([applyAccess]) runs after Mixin: mixins add the real members (privately, as Mixin requires
 * for statics and fields) and this widens them, plus everything the .at files widen.
 */
class Surface private constructor(
    private val at: Map<String, List<AtRule>>,
    private val members: Map<String, List<MemberDecl>>,
) {
    data class AtRule(val vis: Int, val finalChange: Int, val member: String?, val desc: String?)

    data class MemberDecl(val owner: String, val raw: String, val decl: String)

    val owners: Set<String> get() = at.keys + members.keys

    fun touches(internalName: String) = internalName in at || internalName in members

    /** Run-time pass: widen access per .at rules and member declarations. Returns null if untouched. */
    fun applyAccess(bytes: ByteArray): ByteArray? {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val changed = widen(node) or widenInnerEntries(node)
        if (!changed) return null
        val w = ClassWriter(0)
        node.accept(w)
        return w.toByteArray()
    }

    /** Classes whose nested-class entries must agree with widened nested classes. */
    fun referencesTouched(node: ClassNode) = node.innerClasses?.any { ic -> at[ic.name]?.any { it.member == null } == true } == true

    /** Keeps InnerClasses entries consistent with widened nested classes (javac reads flags from them). */
    fun widenInnerEntries(node: ClassNode): Boolean {
        var changed = false
        node.innerClasses?.forEach { ic ->
            at[ic.name]?.filter { it.member == null }?.forEach { r ->
                val n = widenFlags(ic.access, r.vis, r.finalChange)
                if (n != ic.access) { ic.access = n; changed = true }
            }
        }
        return changed
    }

    private fun widen(node: ClassNode): Boolean {
        val wasPrivate = node.methods.filter { it.access and ACC_PRIVATE != 0 && it.name != "<init>" }.map { it.name + it.desc }.toSet()
        val changed = widenMembers(node)
        if (changed && wasPrivate.isNotEmpty()) devirtualize(node, wasPrivate)
        return changed
    }

    /**
     * A private method made non-private must dispatch virtually, as it does in Paper's recompiled
     * source: rewrite the class's own `invokespecial` calls to it (javac emits those for private calls).
     */
    private fun devirtualize(node: ClassNode, wasPrivate: Set<String>) {
        val widened = node.methods.filter { (it.name + it.desc) in wasPrivate && it.access and ACC_PRIVATE == 0 && it.access and ACC_STATIC == 0 }
            .map { it.name + it.desc }.toSet()
        if (widened.isEmpty()) return
        val itf = node.access and ACC_INTERFACE != 0
        for (m in node.methods) {
            for (insn in m.instructions) {
                if (insn is MethodInsnNode && insn.opcode == INVOKESPECIAL && insn.owner == node.name && (insn.name + insn.desc) in widened) {
                    insn.opcode = if (itf) INVOKEINTERFACE else INVOKEVIRTUAL
                }
            }
        }
    }

    private fun widenMembers(node: ClassNode): Boolean {
        var changed = false
        at[node.name]?.forEach { r ->
            when {
                r.member == null -> {
                    val n = widenFlags(node.access, r.vis, r.finalChange)
                    if (n != node.access) { node.access = n; changed = true }
                }
                r.desc == null -> node.fields.filter { it.name == r.member }.forEach { f ->
                    val n = widenFlags(f.access, r.vis, r.finalChange)
                    if (n != f.access) { f.access = n; changed = true }
                }
                else -> node.methods.filter { it.name == r.member && it.desc == r.desc }.forEach { m ->
                    val n = widenFlags(m.access, r.vis, r.finalChange)
                    if (n != m.access) { m.access = n; changed = true }
                }
            }
        }
        members[node.name]?.forEach { d ->
            val p = parse(d, node) ?: return@forEach
            when (p) {
                is Parsed.Field -> node.fields.filter { it.name == p.name && it.desc == p.desc }.forEach { f ->
                    val n = widenFlags(f.access, p.access and VIS_MASK, 0)
                    if (n != f.access) { f.access = n; changed = true }
                }
                is Parsed.Method -> node.methods.filter { it.name == p.name && it.desc == p.desc }.forEach { m ->
                    val n = widenFlags(m.access, p.access and VIS_MASK, 0)
                    if (n != m.access) { m.access = n; changed = true }
                }
                is Parsed.Implements -> if (p.iface !in node.interfaces) { addInterface(node, p.iface); changed = true }
            }
        }
        return changed
    }

    /** Compile-time pass: widen access and add every declared member that the class lacks. */
    fun addMembers(node: ClassNode) {
        widen(node)
        widenInnerEntries(node)
        members[node.name]?.forEach { d ->
            when (val p = parse(d, node) ?: return@forEach) {
                is Parsed.Field -> if (node.fields.none { it.name == p.name }) {
                    node.fields.add(FieldNode(p.access, p.name, p.desc, p.signature, null))
                }
                is Parsed.Method -> if (node.methods.none { it.name == p.name && it.desc == p.desc }) {
                    val m = MethodNode(p.access, p.name, p.desc, p.signature, p.exceptions.toTypedArray())
                    if (p.access and ACC_ABSTRACT == 0) {
                        m.instructions.add(TypeInsnNode(NEW, "java/lang/AbstractMethodError"))
                        m.instructions.add(InsnNode(DUP))
                        m.instructions.add(LdcInsnNode("FissionMC surface stub: ${node.name}.${p.name}"))
                        m.instructions.add(MethodInsnNode(INVOKESPECIAL, "java/lang/AbstractMethodError", "<init>", "(Ljava/lang/String;)V", false))
                        m.instructions.add(InsnNode(ATHROW))
                    }
                    node.methods.add(m)
                }
                is Parsed.Implements -> if (p.iface !in node.interfaces) addInterface(node, p.iface)
            }
        }
    }

    /** Adds an interface; javac reads supertypes from the generic Signature when present, so extend it too. */
    private fun addInterface(node: ClassNode, iface: String) {
        node.interfaces.add(iface)
        node.signature?.let { node.signature = it + "L$iface;" }
    }

    sealed interface Parsed {
        data class Field(val access: Int, val name: String, val desc: String, val signature: String?) : Parsed
        data class Method(val access: Int, val name: String, val desc: String, val signature: String?, val exceptions: List<String>) : Parsed
        data class Implements(val iface: String) : Parsed
    }

    companion object {
        private const val VIS_MASK = ACC_PUBLIC or ACC_PROTECTED or ACC_PRIVATE

        private val MODS = mapOf(
            "public" to ACC_PUBLIC, "protected" to ACC_PROTECTED, "private" to ACC_PRIVATE,
            "static" to ACC_STATIC, "final" to ACC_FINAL, "volatile" to ACC_VOLATILE,
            "transient" to ACC_TRANSIENT, "synchronized" to ACC_SYNCHRONIZED, "abstract" to ACC_ABSTRACT,
            "default" to 0, "native" to ACC_NATIVE,
        )

        private fun rank(vis: Int) = when {
            vis and ACC_PUBLIC != 0 -> 3
            vis and ACC_PROTECTED != 0 -> 2
            vis and ACC_PRIVATE != 0 -> 0
            else -> 1
        }

        fun widenFlags(access: Int, vis: Int, finalChange: Int): Int {
            var a = access
            if (vis != -1 && rank(vis) > rank(access and VIS_MASK)) a = (a and VIS_MASK.inv()) or vis
            if (finalChange < 0) a = a and ACC_FINAL.inv()
            if (finalChange > 0) a = a or ACC_FINAL
            return a
        }

        fun load(dir: File): Surface {
            val at = HashMap<String, MutableList<AtRule>>()
            dir.listFiles { f -> f.name.endsWith(".at") }?.sorted()?.forEach { f ->
                f.readLines().forEachIndexed { i, line0 ->
                    val line = line0.substringBefore('#').trim()
                    if (line.isEmpty()) return@forEachIndexed
                    val parts = line.split(Regex("\\s+"))
                    require(parts.size in 2..3) { "${f.name}:${i + 1}: bad AT line: $line0" }
                    val mod = parts[0]
                    val vis = when (mod.substringBefore('-').substringBefore('+')) {
                        "public" -> ACC_PUBLIC; "protected" -> ACC_PROTECTED; "private" -> ACC_PRIVATE
                        "default" -> 0; else -> error("${f.name}:${i + 1}: bad modifier $mod")
                    }
                    val fc = when { mod.endsWith("-f") -> -1; mod.endsWith("+f") -> 1; else -> 0 }
                    val owner = parts[1].replace('.', '/')
                    var member: String? = null
                    var desc: String? = null
                    if (parts.size == 3) {
                        val m = parts[2]
                        val p = m.indexOf('(')
                        if (p >= 0) { member = m.substring(0, p); desc = m.substring(p) } else member = m
                    }
                    at.getOrPut(owner) { mutableListOf() } += AtRule(vis, fc, member, desc)
                }
            }
            val members = HashMap<String, MutableList<MemberDecl>>()
            File(dir, "members").listFiles { f -> f.name.endsWith(".txt") }?.sorted()?.forEach { f ->
                f.readLines().forEachIndexed { i, line0 ->
                    val line = line0.substringBefore("//").trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                    val colon = line.indexOf(": ")
                    require(colon > 0) { "${f.name}:${i + 1}: expected 'owner: declaration': $line0" }
                    val owner = line.substring(0, colon).trim().replace('.', '/')
                    members.getOrPut(owner) { mutableListOf() } += MemberDecl(owner, "${f.name}:${i + 1}", line.substring(colon + 2).trim().removeSuffix(";"))
                }
            }
            return Surface(at, members)
        }

        /** Formal type parameters of a class (name -> erased first bound) from its Signature attribute. */
        fun classTypeVars(node: ClassNode): Map<String, String> {
            val sig = node.signature ?: return emptyMap()
            if (!sig.startsWith("<")) return emptyMap()
            val out = LinkedHashMap<String, String>()
            var i = 1
            var depth = 0
            while (i < sig.length && sig[i] != '>') {
                val nameEnd = sig.indexOf(':', i)
                val name = sig.substring(i, nameEnd)
                i = nameEnd + 1
                if (sig[i] == ':') i++ // interface bound
                // bound starts here: L...; or T...;
                val start = i
                depth = 0
                while (i < sig.length) {
                    val c = sig[i]
                    if (c == '<') depth++
                    if (c == '>') depth--
                    if (c == ';' && depth == 0) { i++; break }
                    i++
                }
                val bound = sig.substring(start, i)
                out[name] = if (bound.startsWith("L")) bound.substring(1).substringBefore('<').removeSuffix(";") else "java/lang/Object"
                while (i < sig.length && sig[i] == ':') { // extra interface bounds
                    i++
                    depth = 0
                    while (i < sig.length) {
                        val c = sig[i]; if (c == '<') depth++; if (c == '>') depth--
                        if (c == ';' && depth == 0) { i++; break }; i++
                    }
                }
            }
            return out
        }
    }

    private fun parse(d: MemberDecl, node: ClassNode): Parsed? = try {
        parseDecl(d.decl, node)
    } catch (e: Exception) {
        throw IllegalArgumentException("${d.raw}: ${e.message}", e)
    }

    private fun parseDecl(text: String, node: ClassNode): Parsed {
        if (text.startsWith("implements ")) return Parsed.Implements(text.removePrefix("implements ").trim().replace('.', '/'))
        val classVars = classTypeVars(node)
        // modifiers
        var access = 0
        var rest = text
        while (true) {
            val w = rest.substringBefore(' ')
            val f = MODS[w] ?: break
            access = access or f
            rest = rest.substringAfter(' ').trimStart()
        }
        if (node.access and ACC_INTERFACE != 0 && access and ACC_STATIC == 0 && access and ACC_PRIVATE == 0) access = access or ACC_PUBLIC
        // method type params
        var methodParams = emptyList<TypeParam>()
        if (rest.startsWith("<")) {
            val tp = TypeParser(rest, classVars.keys)
            methodParams = tp.parseTypeParams()
            rest = tp.rest().trimStart()
        }
        val vars = classVars.keys + methodParams.map { it.name }
        val erasure = HashMap(classVars)
        methodParams.forEach { tp -> erasure[tp.name] = (tp.bounds.firstOrNull() as? JType.Cls)?.internal ?: "java/lang/Object" }
        val parser = TypeParser(rest, vars)
        val type = parser.parseType()
        val name = parser.ident()
        parser.skipWs()
        if (parser.atEnd()) {
            return Parsed.Field(access, name, type.descriptor(erasure), if (type.generic) type.signature() else null)
        }
        parser.expect('(')
        val params = mutableListOf<JType>()
        parser.skipWs()
        if (parser.peek() != ')') {
            while (true) {
                var p = parser.parseType()
                parser.skipWs()
                if (parser.rest().startsWith("...")) {
                    repeat(3) { parser.next() }
                    p = JType.Arr(p)
                    access = access or ACC_VARARGS
                }
                parser.skipWs()
                if (parser.peek() != ',' && parser.peek() != ')') parser.ident() // parameter name
                parser.skipWs()
                params += p
                if (parser.next() == ')') break
            }
        } else parser.next()
        val exceptions = mutableListOf<String>()
        if (parser.word("throws")) {
            while (true) {
                exceptions += (parser.parseType() as JType.Cls).internal
                parser.skipWs()
                if (parser.peek() == ',') parser.next() else break
            }
        }
        val desc = "(" + params.joinToString("") { it.descriptor(erasure) } + ")" + type.descriptor(erasure)
        val generic = type.generic || params.any { it.generic } || methodParams.isNotEmpty()
        val sig = if (!generic) null else
            (if (methodParams.isEmpty()) "" else "<" + methodParams.joinToString("") { it.signature() } + ">") +
                "(" + params.joinToString("") { it.signature() } + ")" + type.signature()
        if (name == "<init>" || name == node.name.substringAfterLast('/').substringAfterLast('$')) {
            error("constructors cannot be added by Mixin; route call sites through a static factory instead")
        }
        return Parsed.Method(access, name, desc, sig, exceptions)
    }
}
