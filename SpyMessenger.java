package org.itmo;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

class SpyMessenger {
    private final ConcurrentHashMap<String, UserData> users = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Lock lock = new ReentrantLock();

    private static class UserData {
        Deque<Message> messages = new LinkedList<>();
        ScheduledFuture<?> cleanupTask;
        long lastAccess;
    }

    private static class Message {
        String sender;
        String content;
        String passcode;
        ScheduledFuture<?> selfDestruct;

        Message(String sender, String content, String passcode) {
            this.sender = sender;
            this.content = content;
            this.passcode = passcode;
        }
    }

    public void sendMessage(String sender, String receiver, String message, String passcode) {
        lock.lock();
        try {
            UserData user = users.computeIfAbsent(receiver, k -> new UserData());
            Message msg = new Message(sender, message, passcode);

            // Ограничение на 5 сообщений
            if (user.messages.size() >= 5) {
                Message oldest = user.messages.pollFirst();
                if (oldest != null && oldest.selfDestruct != null) {
                    oldest.selfDestruct.cancel(false);
                }
            }

            // Таймер самоуничтожения через 1.5 сек
            msg.selfDestruct = scheduler.schedule(() -> removeMessage(receiver, msg), 1500, TimeUnit.MILLISECONDS);
            user.messages.addLast(msg);

            // Таймер неактивности получателя
            scheduleCleanup(receiver);
        } finally {
            lock.unlock();
        }
    }

    public String readMessage(String user, String passcode) {
        lock.lock();
        try {
            UserData data = users.get(user);
            if (data == null || data.messages.isEmpty()) return null;

            data.lastAccess = System.currentTimeMillis();
            Iterator<Message> iterator = data.messages.iterator();
            
            while (iterator.hasNext()) {
                Message msg = iterator.next();
                if (msg.passcode.equals(passcode)) {
                    if (!msg.sender.equals(user)) {
                        iterator.remove();
                        notifyHackAttempt(msg.sender);
                        return null;
                    }
                    msg.selfDestruct.cancel(false);
                    iterator.remove();
                    return msg.content;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private void scheduleCleanup(String user) {
        UserData data = users.get(user);
        if (data != null && (data.cleanupTask == null || data.cleanupTask.isDone())) {
            data.cleanupTask = scheduler.schedule(() -> checkInactivity(user), 3000, TimeUnit.MILLISECONDS);
        }
    }

    private void checkInactivity(String user) {
        lock.lock();
        try {
            UserData data = users.get(user);
            if (data != null && System.currentTimeMillis() - data.lastAccess >= 3000) {
                data.messages.forEach(msg -> msg.selfDestruct.cancel(false));
                users.remove(user);
            }
        } finally {
            lock.unlock();
        }
    }

    private void removeMessage(String user, Message msg) {
        lock.lock();
        try {
            UserData data = users.get(user);
            if (data != null) {
                data.messages.remove(msg);
            }
        } finally {
            lock.unlock();
        }
    }

    private void notifyHackAttempt(String sender) {
        System.out.println("[ALERT] User " + sender + " detected hacking attempt!");
    }
}