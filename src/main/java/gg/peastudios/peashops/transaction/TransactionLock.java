package gg.peastudios.peashops.transaction;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

// per-shop / per-network reentrant locks with timeout. acquired before any
// transaction touches inventory or economy. released in a finally block.
//
// the dupe surface this prevents:
//   - two simultaneous buys on the same shop both passing the stock check
//   - hopper restocking the chest mid-trade and the trade reading stale stock
//   - linked-network satellites both pulling from a central chest in parallel
//
// per-network locks resolve to the central chest's UUID so all satellites
// queue behind the central. prevents fan-out cache-coherence bugs.
//
// not using synchronized blocks because they don't have a timeout; if a
// transaction hangs (eg vault provider hangs on .has()), every other player
// blocks forever. with timeout, the worst case is a "shop busy" message.
public final class TransactionLock {

    private final ConcurrentHashMap<UUID, ReentrantLock> shopLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> networkLocks = new ConcurrentHashMap<>();

    /**
     * acquire the lock for a single shop. returns null if timeout exceeded.
     * caller MUST release in a finally.
     */
    public ReentrantLock acquireShop(UUID shopId, long timeoutMs) {
        ReentrantLock lock = shopLocks.computeIfAbsent(shopId, k -> new ReentrantLock());
        try {
            if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * acquire the lock for an entire linked network. all satellites queue here.
     * returns null on timeout.
     */
    public ReentrantLock acquireNetwork(UUID networkCentralId, long timeoutMs) {
        ReentrantLock lock = networkLocks.computeIfAbsent(networkCentralId, k -> new ReentrantLock());
        try {
            if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    public void release(ReentrantLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) lock.unlock();
    }

    /**
     * best-effort cleanup. call when a shop is permanently deleted so the
     * lock map doesn't grow unbounded over a server's lifetime.
     */
    public void forget(UUID shopId) {
        shopLocks.remove(shopId);
    }

    public void forgetNetwork(UUID networkCentralId) {
        networkLocks.remove(networkCentralId);
    }

    /** rough size — useful for /shopadmin stats. */
    public int trackedShops() { return shopLocks.size(); }
    public int trackedNetworks() { return networkLocks.size(); }
}
