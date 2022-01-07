package nl.inl.blacklab.search.results;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

public class ReadWriteNoopLock  implements ReadWriteLock {
    public static final class NoopLock implements  Lock {
        @Override
        public void lock() {

        }

        @Override
        public void lockInterruptibly() throws InterruptedException {

        }

        @Override
        public boolean tryLock() {
            return true;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return true;
        }

        @Override
        public void unlock() {

        }

        @Override
        public Condition newCondition() {
            return null;
        }
    }

    private final Lock noopLock;
    public ReadWriteNoopLock() {
        this.noopLock = new NoopLock();
    }
    @Override
    public Lock readLock() {
        return noopLock;
    }

    @Override
    public Lock writeLock() {
        return noopLock;
    }
}
