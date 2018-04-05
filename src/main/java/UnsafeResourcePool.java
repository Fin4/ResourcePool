public interface UnsafeResourcePool<R> extends ResourcePool<R> {

    void closeNow();

    boolean removeNow(R resource);
}
