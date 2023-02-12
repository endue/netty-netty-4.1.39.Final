package io.netty.example.common;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;

import java.util.concurrent.CountDownLatch;

public class FastThreadLocalTest {

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        FastThreadLocal<String> ftl1 = new FastThreadLocal<>();
        FastThreadLocal<String> ftl2 = new FastThreadLocal<>();

        FastThreadLocalThread t1 = new FastThreadLocalThread(() -> {
            ftl1.set("a");
            ftl1.set("b");

            ftl2.set("b");
            ftl2.set("b");

            countDownLatch.countDown();
        });

        t1.start();

        countDownLatch.await();

    }
}
