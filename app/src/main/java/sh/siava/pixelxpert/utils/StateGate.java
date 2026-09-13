package sh.siava.pixelxpert.utils;

import android.os.SystemClock;

/**
 * A resettable one-shot readiness gate. Replaces the previous {@code CountDownLatch(1)} fields
 * that could never be re-armed: once counted down they stayed "ready" forever, so a later
 * preference reset or a root-service disconnect + reconnect was reported to the splash screen as
 * if it had never happened.
 */
public final class StateGate {
	private final Object mLock = new Object();
	private boolean mOpen;

	public StateGate(boolean initiallyOpen) {
		mOpen = initiallyOpen;
	}

	public void open() {
		synchronized (mLock) {
			mOpen = true;
			mLock.notifyAll();
		}
	}

	public void close() {
		synchronized (mLock) {
			mOpen = false;
		}
	}

	public boolean isOpen() {
		synchronized (mLock) {
			return mOpen;
		}
	}

	/** @return true if the gate is open, false if the timeout elapsed while still closed. */
	public boolean await(long timeoutMs) {
		long deadline = SystemClock.uptimeMillis() + timeoutMs;
		synchronized (mLock) {
			while (!mOpen) {
				long remaining = deadline - SystemClock.uptimeMillis();
				if (remaining <= 0) return false;
				try {
					mLock.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return true;
		}
	}
}
