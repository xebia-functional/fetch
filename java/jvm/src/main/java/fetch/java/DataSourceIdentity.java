package fetch.java;

public final class DataSourceIdentity<I> {
  private final String name;
  private final I i;

  public DataSourceIdentity(String name, I identity) {
    this.name = name;
    this.i = identity;
  }

  public String getName() {
    return name;
  }

  public I getIdentity() {
    return i;
  }
}
