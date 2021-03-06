package rjs.ukanren

object Main {
  // Program proceeds by the application of a Goal to a State / pursuing a goal in a state
  // Goal ~ Predicate
  // Applying/Pursuing a goal to/in a State results in Either[
  //   A sequence of enlarged States,
  //   failure
  // ]
  //
  // Use functions to simulate relations.
  // Jargony version:
  //
  // An arbitrary
  //  [An] n-ary relation is viewed as an (n−1)-ary partial function,
  //  mapping tuples of domain elements into a linearized
  //  submultiset of elements of the codomain over which the initial
  //  relation holds
  //
  // Collection of Goals can be satisfied by 0 or more states
  // Result of the program is a stream of satisfying states
  //
  // Goal constructors:
  //   ≡ (unify) succeeds when its two arguments unify
  //   call/fresh (fresh) creates a new logic variable
  //   disj creates a new goal that succeeds if either of the goal arguments succeeds
  //   conj creates a new goal that succeeds if the second goal argument can be achieved in the stream generated by the first
  //
  // State: Pair(Substitution: Seq[(_, _)], FreshVariableCounter: Int >= 0)
  //
  // Usage:
  //   Apply a goal to a state
  //   (Goal can be arbitrarily complex)
  //   Get back a stream of states (possibly infinite)
  // Example:
  //   Applying λ(q). q unify 5 to ((Seq(), 0): State) gets you Seq(Seq(0, 5), 1)
  //   Applying
  //     conj(
  //       λ(a). a unify 7,
  //       λ(b). disj( b unify 5 , b unify 6 )
  //     ) to ((Seq(), 0): State) gets you
  //   Seq(
  //     (Seq((1, 5), (0, 7)), 2),
  //     (Seq((1, 6), (0, 7)), 2)
  //   )


  // Implementation:

  // Variables themselves are represented as vectors that hold their variable index.
  // Variable equality is determined by coincidence of indices in vectors.



  def main(args: Array[String]): Unit = {
    import Core._
    val emptyState: State = State(Substitution(), 0)

    val five: Program = callFresh { x => equiv(x, Atom("5"))}
    def fives_(x: Term): Program = disj(equiv(x, Atom("5")), zzz(fives_(x)))
    def fives = callFresh(fives_)

    def a_and_b = conj(
      callFresh({a => equiv(a, Atom("7"))}),
      callFresh({a => disj(equiv(a, Atom("5")), equiv(a, Atom("6")))})
    )

    def klistToStream[A](kList: => DelayableStream[A]): Stream[A] = kList match {
      case Nil => Stream.empty[A]
      case Cons(x, xs) => x #:: klistToStream(xs)
      case Delay(xs) => klistToStream(xs)
    }

    def runTest(p: Program) = klistToStream(p(emptyState))
    println("a_and_b")
    runTest(a_and_b).foreach(println)
    println("five")
    runTest(five).foreach(println)
    println("fives")
    runTest(fives).take(10).foreach(println)
  }

}


object Core {
  case class Substitution(private val repr: Map[Vaar, Term] = Map()) {
    def get(key: Vaar) = repr.get(key)
    def +(entry: (Vaar, Term)) = Substitution(repr + entry)
  }
  case class State(substitution: Substitution, counter: Int)
  type Program = State => DelayableStream[State]

  sealed trait Term
  case class Atom(str: String) extends Term
  case class Pair(t1: Term, t2: Term) extends Term
  case class Vaar(vaar: Int) extends Term

  sealed trait DelayableStream[+A]
  case object Nil extends DelayableStream[Nothing]
  class Cons[A](val head: A, _tail: => DelayableStream[A]) extends DelayableStream[A] {lazy val tail = _tail}
  object Cons {
    def apply[A](head: A, tail: => DelayableStream[A]) = new Cons(head, tail)
    def unapply[A](cons: Cons[A]): Option[(A, DelayableStream[A])] = Some((cons.head, cons.tail))
  }
  class Delay[A](delayed: => DelayableStream[A]) extends DelayableStream[A] {
    lazy val getDelayed: DelayableStream[A] = delayed
  }
  object Delay {
    def apply[A](delayed: => DelayableStream[A]): Delay[A] = new Delay(delayed)
    def unapply[A](delay: Delay[A]): Option[DelayableStream[A]] = Some(delay.getDelayed)
  }

  object KListOps {
    def mzero[A]: DelayableStream[A] = Nil

    def pure[A](x: A): DelayableStream[A] = Cons(x, Nil)

    def flatMap[A, B](fa: DelayableStream[A])(f: A => DelayableStream[B]): DelayableStream[B] = fa match {
      case Nil => mzero[B]
      case Cons(x, xs) => mplus(f(x), flatMap(xs)(f))
      case Delay(xs) => Delay(flatMap(xs)(f))
    }

    def mplus[A](xs: DelayableStream[A], y: => DelayableStream[A]): DelayableStream[A] = xs match {
      case Nil => y
      case Cons(x, _xs) => Cons(x, mplus(y, _xs))
      case Delay(_xs) => Delay(mplus(y, _xs))
    }
  }
  import KListOps._

  private def walk(term: Term, substitution: Substitution): Term = term match {
    case v @ Vaar(_) => substitution.get(v) match {
      case None => v: Term
      case Some(nextVar) => walk(nextVar, substitution)
    }
    case _ => term
  }

  private def unify(term1: Term, term2: Term, substitution: Substitution): Option[Substitution] =
    (walk(term1, substitution), walk(term2, substitution)) match {
      case (Vaar(v1), Vaar(v2)) if v1 == v2 => Some(substitution)
      case (vaar @ Vaar(_), notAVar) => Some(substitution + (vaar -> notAVar))
      case (notAVar, vaar @ Vaar(_)) => Some(substitution + (vaar -> notAVar))
      case (Atom(a1), Atom(a2)) if a1 == a2 => Some(substitution)
      case (Pair(pair1_1, pair1_2), Pair(pair2_1, pair2_2)) =>
        unify(pair1_1, pair2_1, substitution).flatMap(unify(pair1_2, pair2_2, _))
      case _ => None
    }

  def zzz(g: => Program): Program = { sc => Delay(g(sc)) }

  def equiv(u: Term, v: Term): Program = { case State(substitution, counter) =>
    unify(u, v, substitution).fold(
      mzero[State]
    )(
      newSubstitution => pure(State(newSubstitution, counter))
    )
  }

  def callFresh(f: Term => Program): Program = { case State(substitution, counter) =>
    f(Vaar(counter))(State(substitution, counter + 1))
  }

  def disj(g1: Program, g2: Program): Program = { state =>
    mplus(g1(state), g2(state))
  }

  def conj(g1: Program, g2: Program): Program = { state =>
    flatMap(g1(state))(g2)
  }
}
