import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class UnsafeResourcePoolImpl<R> implements UnsafeResourcePool<R> {

    private volatile boolean isOpened;
    private volatile boolean isClosed;

    private Set<R> pool;
    private Set<R> resourcesToRemove;
    private Set<R> acquiredResources;


    private Lock lock = new ReentrantLock();
    private Condition allReleased = lock.newCondition();
    private Condition notEmpty = lock.newCondition();


    public UnsafeResourcePoolImpl() {
    }

    @Override
    public void open() {
        if (!this.isOpened) {
            lock.lock();
            try {
                if (!this.isOpened) {
                    this.pool = new HashSet<>();
                    this.resourcesToRemove = new HashSet<>();
                    this.acquiredResources = new HashSet<>();
                    this.isOpened = true;
                    this.isClosed = false;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public boolean isOpen() {
        return isOpened;
    }

    @Override
    public void close() {

        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");

        lock.lock();

        try {
            while (!acquiredResources.isEmpty())
                allReleased.await();

            pool.clear();
            acquiredResources.clear();
            resourcesToRemove.clear();
            isClosed = true;
            isOpened = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public R acquire() {

        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");

        lock.lock();

        try {
            while (pool.size() == 0) {
                notEmpty.await();
            }
            R acquired = pool.stream().findFirst().get();
            if (pool.remove(acquired)) {
                acquiredResources.add(acquired);
            }

            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }


    }

    @Override
    public R acquire(long timeout, TimeUnit timeUnit) {

        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");

        long nanos = timeUnit.toNanos(timeout);

        lock.lock();

        try {
            while (pool.size() == 0) {
                if (nanos <= 0)
                    return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            R acquired = pool.stream().findFirst().get();
            if (pool.remove(acquired)) {
                acquiredResources.add(acquired);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(R resource) {

        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");

        lock.lock();

        try {
            if (acquiredResources.contains(resource) && !resourcesToRemove.contains(resource)) {
                pool.add(resource);
                notEmpty.signal();
            } else if (acquiredResources.contains(resource) && resourcesToRemove.contains(resource)) {
                acquiredResources.remove(resource);
            }
            if (acquiredResources.isEmpty()) {
                allReleased.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean add(R resource) {

        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");

        lock.lock();

        try {

            if (acquiredResources.contains(resource)) {
                return false;
            } else if (resourcesToRemove.remove(resource)) {
                return true;
            } else {
                boolean added = pool.add(resource);
                if (added) notEmpty.signal();
                return added;
            }
        } finally {
            lock.unlock();
        }

    }


    @Override
    public boolean remove(R resource) {

        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");


        lock.lock();

        try {
            R r = pool.stream()
                    .filter(e -> e.equals(resource) && !acquiredResources.contains(resource))
                    .findFirst()
                    .orElse(null);

            if (r != null) {
                return pool.remove(r);
            } else {
                resourcesToRemove.add(resource);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean removeNow(R resource) {
        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");

        R r = pool.stream()
                .filter(e -> e.equals(resource) && !acquiredResources.contains(resource))
                .findFirst()
                .orElse(null);

        if (r != null) {
            return pool.remove(r);
        } else {
            resourcesToRemove.add(resource);
            return true;
        }
    }

    @Override
    public void closeNow() {

        if (!isOpened) throw new IllegalStateException("Object pool isn't opened yet.");

        if (isClosed) throw new IllegalStateException("Object pool is already closed.");

        pool.clear();
        acquiredResources.clear();
        resourcesToRemove.clear();
        isClosed = true;
        isOpened = false;

    }

}
