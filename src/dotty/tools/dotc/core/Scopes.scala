/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */

package dotty.tools.dotc
package core

import Symbols._
import Names._
import Periods._
import Decorators._
import Contexts._
import Denotations._
import util.Texts._
import SymDenotations.NoDenotation

object Scopes {

  /** Maximal fill factor of hash table */
  private final val FillFactor = 2.0/3.0

  /** A hashtable is created once current size exceeds MinHash * FillFactor
   *  The initial hash table has twice that size (i.e 24).
   */
  private final val MinHash = 12

  /** The maximal permissible number of recursions when creating
   *  a hashtable
   */
  private final val MaxRecursions = 1000

  class ScopeEntry private[Scopes] (val sym: Symbol, val owner: Scope) {

    /** the next entry in the hash bucket
     */
    var tail: ScopeEntry = null

    /** the preceding entry in this scope
     */
    var prev: ScopeEntry = null

    override def toString: String = sym.toString
  }

  /** A scope contains a set of symbols. It can be an extension
   *  of some outer scope, from which it inherits all symbols.
   *  This class does not have any methods to add symbols to a scope
   *  or to delete them. These methods are provided by subclass
   *  MutableScope.
   */
  abstract class Scope extends Showable with Iterable[Symbol] {

    /** The last scope-entry from which all others are reachable via `prev` */
    private[dotc] def lastEntry: ScopeEntry

    /** The number of symbols in this scope (including inherited ones
     *  from outer scopes).
     */
    def size: Int

    /** The number of outer scopes from which symbols are inherited */
    def nestingLevel: Int

    /** The symbols in this scope in the order they were entered;
     *  inherited from outer ones first.
     */
    def toList: List[Symbol]

    /** Return all symbols as an iterator in the order they were entered in this scope.
     */
    def iterator: Iterator[Symbol] = toList.iterator

    /** Returns a new scope with the same content as this one. */
    def cloneScope(implicit ctx: Context): Scope

    /** Is the scope empty? */
    override def isEmpty: Boolean = lastEntry eq null

    /** Lookup a symbol entry matching given name. */
    def lookupEntry(name: Name)(implicit ctx: Context): ScopeEntry

    /** Lookup next entry with same name as this one */
    def lookupNextEntry(entry: ScopeEntry)(implicit ctx: Context): ScopeEntry

    /** Lookup a symbol */
    final def lookup(name: Name)(implicit ctx: Context): Symbol = {
      val e = lookupEntry(name)
      if (e eq null) NoSymbol else e.sym
    }

    /** Returns an iterator yielding every symbol with given name in this scope.
     */
    final def lookupAll(name: Name)(implicit ctx: Context): Iterator[Symbol] = new Iterator[Symbol] {
      var e = lookupEntry(name)
      def hasNext: Boolean = e ne null
      def next(): Symbol = { val r = e.sym; e = lookupNextEntry(e); r }
    }

    /** The denotation set of all the symbols with given name in this scope */
    final def denotsNamed(name: Name)(implicit ctx: Context): PreDenotation = {
      var syms: PreDenotation = NoDenotation
      var e = lookupEntry(name)
      while (e != null) {
        syms = syms union e.sym.denot
        e = lookupNextEntry(e)
      }
      syms
    }

    /** Cast this scope to a mutable scope */
    final def openForMutations: MutableScope = this.asInstanceOf[MutableScope]

    final def toText(implicit ctx: Context): Text = ctx.toText(this)
  }

  /** A subclass of Scope that defines methods for entering and
   *  unlinking entries.
   *  Note: constructor is protected to force everyone to use the factory methods newScope or newNestedScope instead.
   *  This is necessary because when run from reflection every scope needs to have a
   *  SynchronizedScope as mixin.
   */
  class MutableScope protected[Scopes](initElems: ScopeEntry, initSize: Int, val nestingLevel: Int = 0)
      extends Scope {

    protected[Scopes] def this(base: Scope)(implicit ctx: Context) = {
      this(base.lastEntry, base.size, base.nestingLevel + 1)
      ensureCapacity(MinHash)(ctx) // WTH? it seems the implicit is not in scope for a secondary constructor call.
    }

    def this() = this(null, 0, 0)

    private[dotc] var lastEntry: ScopeEntry = initElems

    /** The size of the scope */
    private[this] var _size = initSize

    override final def size = _size
    private def size_= (x: Int) = _size = x

    /** the hash table
     */
    private var hashTable: Array[ScopeEntry] = null

    /** a cache for all elements, to be used by symbol iterator.
     */
    private var elemsCache: List[Symbol] = null

    def cloneScope(implicit ctx: Context): MutableScope = newScopeWith(this.toList: _*)

    /** create and enter a scope entry */
    protected def newScopeEntry(sym: Symbol)(implicit ctx: Context): ScopeEntry = {
      ensureCapacity(if (hashTable ne null) hashTable.length else MinHash)
      val e = new ScopeEntry(sym, this)
      e.prev = lastEntry
      lastEntry = e
      if (hashTable ne null) enterInHash(e)
      size += 1
      elemsCache = null
      e
    }

    private def enterInHash(e: ScopeEntry)(implicit ctx: Context): Unit = {
      val idx = e.sym.name.hashCode & (hashTable.length - 1)
      e.tail = hashTable(idx)
      assert(e.tail != e)
      hashTable(idx) = e
    }

    /** enter a symbol in this scope. */
    final def enter[T <: Symbol](sym: T)(implicit ctx: Context): T = {
      if (sym.isType) assert(lookup(sym.name) == NoSymbol, sym.debugString) // !!! DEBUG
      newScopeEntry(sym)
      sym
    }

    /** enter a symbol, asserting that no symbol with same name exists in scope */
    final def enterUnique(sym: Symbol)(implicit ctx: Context) {
      assert(lookup(sym.name) == NoSymbol, (sym.showLocated, lookup(sym.name).showLocated))
      enter(sym)
    }

    private def ensureCapacity(tableSize: Int)(implicit ctx: Context): Unit =
      if (size >= tableSize * FillFactor) createHash(tableSize * 2)

    private def createHash(tableSize: Int)(implicit ctx: Context): Unit =
      if (size > tableSize * FillFactor) createHash(tableSize * 2)
      else {
        hashTable = new Array[ScopeEntry](tableSize)
        enterAllInHash(lastEntry)
      }

    private def enterAllInHash(e: ScopeEntry, n: Int = 0)(implicit ctx: Context) {
      if (e ne null) {
        if (n < MaxRecursions) {
          enterAllInHash(e.prev, n + 1)
          enterInHash(e)
        } else {
          var entries: List[ScopeEntry] = List()
          var ee = e
          while (ee ne null) {
            entries = ee :: entries
            ee = ee.prev
          }
          entries foreach enterInHash
        }
      }
    }

    /** Remove entry from this scope (which is required to be present) */
    final def unlink(e: ScopeEntry)(implicit ctx: Context) {
      if (lastEntry == e) {
        lastEntry = e.prev
      } else {
        var e1 = lastEntry
        while (e1.prev != e) e1 = e1.prev
        e1.prev = e.prev
      }
      if (hashTable ne null) {
        val index = e.sym.name.hashCode & (hashTable.length - 1)
        var e1 = hashTable(index)
        if (e1 == e)
          hashTable(index) = e.tail
        else {
          while (e1.tail != e) e1 = e1.tail;
          e1.tail = e.tail
        }
      }
      elemsCache = null
      size -= 1
    }

    /** remove symbol from this scope if it is present */
    final def unlink(sym: Symbol)(implicit ctx: Context) {
      var e = lookupEntry(sym.name)
      while (e ne null) {
        if (e.sym == sym) unlink(e);
        e = lookupNextEntry(e)
      }
    }

    /** Lookup a symbol entry matching given name.
     */
    override final def lookupEntry(name: Name)(implicit ctx: Context): ScopeEntry = {
      var e: ScopeEntry = null
      if (hashTable ne null) {
        e = hashTable(name.hashCode & (hashTable.length - 1))
        while ((e ne null) && e.sym.name != name) {
          e = e.tail
        }
      } else {
        e = lastEntry
        while ((e ne null) && e.sym.name != name) {
          e = e.prev
        }
      }
      e
    }

    /** lookup next entry with same name as this one */
    override final def lookupNextEntry(entry: ScopeEntry)(implicit ctx: Context): ScopeEntry = {
      var e = entry
      if (hashTable ne null)
        do { e = e.tail } while ((e ne null) && e.sym.name != entry.sym.name)
      else
        do { e = e.prev } while ((e ne null) && e.sym.name != entry.sym.name)
      e
    }

    /** Returns all symbols as a list in the order they were entered in this scope.
     *  Does _not_ include the elements of inherited scopes.
     */
    override final def toList: List[Symbol] = {
      if (elemsCache eq null) {
        elemsCache = Nil
        var e = lastEntry
        while ((e ne null) && e.owner == this) {
          elemsCache = e.sym :: elemsCache
          e = e.prev
        }
      }
      elemsCache
    }

    /** Vanilla scope - symbols are stored in declaration order.
     */
    final def sorted: List[Symbol] = toList

    override def foreach[U](p: Symbol => U): Unit = toList foreach p

    final def filteredScope(p: Symbol => Boolean)(implicit ctx: Context): MutableScope = {
      val unfiltered = toList
      val filtered = unfiltered filterConserve p
      if (filtered eq unfiltered) this
      else newScopeWith(filtered: _*)
    }
  }

  /** Create a new scope */
  def newScope: MutableScope = new MutableScope()

  /** Create a new scope nested in another one with which it shares its elements */
  def newNestedScope(outer: Scope)(implicit ctx: Context): MutableScope = new MutableScope(outer)

  /** Create a new scope with given initial elements */
  def newScopeWith(elems: Symbol*)(implicit ctx: Context): MutableScope = {
    val scope = newScope
    elems foreach scope.enter
    scope
  }

  /** Create new scope for the members of package `pkg` */
  def newPackageScope(pkgClass: Symbol): MutableScope = newScope

  /** Transform scope of members of `owner` using operation `op`
   *  This is overridden by the reflective compiler to avoid creating new scopes for packages
   */
  def scopeTransform(owner: Symbol)(op: => MutableScope): MutableScope = op

  /** The empty scope (immutable).
   */
  object EmptyScope extends Scope {
    override def lastEntry = null
    override def size = 0
    override def nestingLevel = 0
    override def toList = Nil
    override def cloneScope(implicit ctx: Context): Scope = this
    override def lookupEntry(name: Name)(implicit ctx: Context): ScopeEntry = null
    override def lookupNextEntry(entry: ScopeEntry)(implicit ctx: Context): ScopeEntry = null
  }

  /** A class for error scopes (mutable)
   */
  class ErrorScope(owner: Symbol) extends MutableScope
}
