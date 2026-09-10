package com.herald.tools;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BrowserCancellationTest {
    @Test void interruptedQueuedOperationNeverExecutesItsAction() throws Exception {
        try(var browser=new BrowserTools(mock(BrowserApproval.class),"http://127.0.0.1:8765","",true,1,60,true)) {
            var worker=(ScheduledExecutorService)ReflectionTestUtils.getField(browser,"worker");
            var occupied=new CountDownLatch(1); var release=new CountDownLatch(1); var finished=new CountDownLatch(1);
            AtomicInteger actions=new AtomicInteger();
            worker.submit(() -> { occupied.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) {Thread.currentThread().interrupt();} });
            assertThat(occupied.await(2,TimeUnit.SECONDS)).isTrue();
            Thread caller=new Thread(() -> {
                try { ReflectionTestUtils.invokeMethod(browser,"run","fixture",(Callable<String>)() -> { actions.incrementAndGet(); return "executed"; }); }
                finally { finished.countDown(); }
            });
            caller.start();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(caller.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<until) Thread.sleep(5);
            assertThat(caller.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            caller.interrupt();
            assertThat(finished.await(2,TimeUnit.SECONDS)).isTrue(); release.countDown();
            worker.submit(() -> {}).get(2,TimeUnit.SECONDS);
            assertThat(actions.get()).isZero();
        }
    }
}
