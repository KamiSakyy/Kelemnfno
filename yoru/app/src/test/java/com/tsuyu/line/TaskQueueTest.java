package com.tsuyu.line;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class TaskQueueTest {
    @Test public void overloadNeverRunsWorkOnCaller() throws Exception {
        ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new TaskQueue.Policy());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            pool.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> queued=pool.submit(()->{});
            AtomicBoolean ran=new AtomicBoolean();
            Future<?> rejected=pool.submit(()->ran.set(true));
            assertTrue(rejected.isCancelled());assertFalse(ran.get());assertFalse(queued.isCancelled());
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test public void cancelledQueuedTasksFreeCapacity() throws Exception {
        ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new TaskQueue.Policy());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            pool.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> stale=pool.submit(()->fail("stale work executed"));stale.cancel(true);
            Future<?> fresh=pool.submit(()->{});assertFalse(fresh.isCancelled());
            release.countDown();fresh.get(2,TimeUnit.SECONDS);
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test public void scopeCancelsRunningAndQueuedWork() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        TaskQueue.Scope scope=new TaskQueue.Scope();CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        try {
            Future<?> running=scope.submit(pool,()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}},null);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> queued=scope.submit(pool,()->fail("obsolete task"),null);
            scope.cancel();assertTrue(running.isCancelled());assertTrue(queued.isCancelled());assertTrue(interrupted.await(2,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
    @Test public void cancellationSurvivesConsumedInterrupt() throws Exception {
        ExecutorService pool=new TaskQueue.Executor(1,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),Executors.defaultThreadFactory(),new TaskQueue.Policy());
        CountDownLatch entered=new CountDownLatch(1),checked=new CountDownLatch(1);
        AtomicBoolean stopped=new AtomicBoolean();
        try {
            Future<?> future=pool.submit(()->{
                entered.countDown();
                try{new CountDownLatch(1).await();}catch(InterruptedException ignored){}
                try{TaskQueue.check();}catch(java.io.InterruptedIOException expected){stopped.set(true);}
                checked.countDown();
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));future.cancel(true);
            assertTrue(checked.await(2,TimeUnit.SECONDS));assertTrue(stopped.get());
            pool.submit(()->{try{TaskQueue.check();}catch(java.io.IOException e){throw new AssertionError(e);}}).get(2,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }
}
