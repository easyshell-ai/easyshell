package com.easyshell.server.config;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/** Per-instance gate; initially closed even when another instance has migrated. */
@Component
public class HostAclStartupBarrier implements DisposableBean {
    enum State { CLOSED, OPEN, ABORTED }

    private volatile State state = State.CLOSED;

    public boolean isOpen() { return state == State.OPEN; }

    synchronized void open() {
        if (state == State.CLOSED) {
            state = State.OPEN;
            notifyAll();
        }
    }

    synchronized void abort() {
        state = State.ABORTED;
        notifyAll();
    }

    synchronized boolean awaitOpen() {
        while (state == State.CLOSED) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return state == State.OPEN && !Thread.currentThread().isInterrupted();
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) { abort(); }

    @Override
    public void destroy() { abort(); }
}
