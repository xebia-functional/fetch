package fetch.java.util;

public class Pair<A, B> {
  private final A a;
  private final B b;

  private Pair(A a, B b) {
    this.a = a;
    this.b = b;
  }

  public A getA() {
    return a;
  }

  public B getB() {
    return b;
  }

  public static <AA, BB> Pair<AA, BB> of(AA a, BB b) {
    return new Pair<>(a, b);
  }
}
