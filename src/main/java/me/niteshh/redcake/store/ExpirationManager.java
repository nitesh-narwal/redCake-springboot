package me.niteshh.redcake.store;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class ExpirationManager {

    private final PriorityQueue<ExpirationEntry> queue = new PriorityQueue<>(
            Comparator.comparingLong(ExpirationEntry::expiresAt)
    );

    private final Object lock = new Object();

    private volatile boolean running = true;  // Flag to control the running state of the expiration thread and ensure visibility across threads

    private Thread workerThread;


    @Setter
    private Consumer<ExpirationEntry> expirationHandler;

    public void schedule(String key, long expiresAt, long version) {

        synchronized (lock) {
            queue.offer(new ExpirationEntry(key, expiresAt, version));  // Add the new expiration entry to the priority queue

            /*
             * Wake the worker because the newly
             * inserted expiration may be earlier
             * than the current queue head.
             */
            lock.notifyAll();
        }
    }

    @PostConstruct
    public void start() {
        workerThread = new Thread(this::expirationLoop, "RedCake-ExpirationThread");
        workerThread.start();
    }

    private void expirationLoop() {
        while (running) {
            try{
                ExpirationEntry entry;
                synchronized (lock) {
                    while (queue.isEmpty() && running) {
                        lock.wait();  // Wait until there is an expiration entry to process or the manager is stopped
                    }

                    if (!running) {
                        break;  // Exit the loop if the manager is stopped
                    }

                    entry = queue.peek();  // Get the earliest expiration entry without removing it

                    long now = System.currentTimeMillis();
                    if (entry.expiresAt() > now) {
                        lock.wait(entry.expiresAt() - now);  // Wait until the key is due to expire
                        continue;  // Re-evaluate the queue after waking up
                    }

                    queue.poll();  // Remove the expired entry from the queue
                }

                Consumer<ExpirationEntry> handler = expirationHandler;
                if (handler != null) {
                    handler.accept(entry);
                }
            }catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }catch (Exception e){
                System.err.println("Expiration worker error: " + e.getMessage());
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }

        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
