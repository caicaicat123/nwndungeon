package nwndungeon;

/** 战斗怪物失踪后的等待与重试预算。时间由调用方提供，便于离线验证。 */
public final class SpawnRecovery {
    public enum Decision { WAIT, RETRY, ABORT }
    public static final long RETRY_DELAY_MS = 5_000L;
    public static final long LOAD_TIMEOUT_MS = 30_000L;
    public static final int MAX_RETRIES = 3;
    private long missingSince = -1;
    private long lastAttempt = -1;
    private int attempts;

    /** 找回原实体时结束本次失踪等待，但不重置整局的补刷预算。 */
    public void found() {
        missingSince = -1;
        lastAttempt = -1;
    }

    public int attempts() { return attempts; }

    public Decision missing(long now, boolean entitiesLoaded) {
        if (missingSince < 0) {
            missingSince = now;
        }
        if (!entitiesLoaded) {
            return now - missingSince >= LOAD_TIMEOUT_MS ? Decision.ABORT : Decision.WAIT;
        }
        long since = lastAttempt < 0 ? missingSince : lastAttempt;
        if (now - since < RETRY_DELAY_MS) {
            return Decision.WAIT;
        }
        if (attempts >= MAX_RETRIES) {
            return Decision.ABORT;
        }
        attempts++;
        lastAttempt = now;
        return Decision.RETRY;
    }
}
