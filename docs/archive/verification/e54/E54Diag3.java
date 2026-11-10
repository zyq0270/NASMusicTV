import com.nasmusic.tv.visualizer.arcade.GameInput;
import com.nasmusic.tv.visualizer.arcade.bot.ContraBot;
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim;

/**
 * 第三轮 bot 退化定位：在 Java 侧对 decide() 的输出做后处理，模拟「禁用某项智能」，
 * 一次跑完多组组合，找出让 8 关重新可通关的最小修复集合。
 *
 * mask 位：
 *   1  NO_UP           强制 up=false（撤销斜上瞄准）
 *   2  NO_DOWN         强制 down=false（撤销蹲射，crouch 会让 speed=0）
 *   4  NO_BOSS_LEFT    boss 战强制 left=false（保住 facing=1）
 *   8  NO_LEFT         全局强制 left=false
 *  16  JUMP_THROTTLE   跳跃节流：距上次有效跳 < 18 帧就不许再跳（近似恢复窄闪避窗）
 *  32  WATCHDOG        推进看门狗：240 帧没刷新最远 x ⇒ 120 帧恢复模式（禁 up/down/left，强制 right）
 *  64  FORCE_RESTART   240 帧没刷新最远 x ⇒ restartLevel()（近似恢复 stuck 4 次就重置）
 */
public class E54Diag3 {

    private static final int NO_UP = 1;
    private static final int NO_DOWN = 2;
    private static final int NO_BOSS_LEFT = 4;
    private static final int NO_LEFT = 8;
    private static final int JUMP_THROTTLE = 16;
    private static final int WATCHDOG = 32;
    private static final int FORCE_RESTART = 64;
    private static final int FORCE_WPN_R = 128;   // 每帧强制普通枪
    private static final int FORCE_WPN_M = 256;   // 每帧强制机枪（净增强的单发直线）
    private static final int FORCE_WPN_L = 512;   // 每帧强制激光

    private static final java.lang.reflect.Field WEAPON_FIELD;

    static {
        try {
            WEAPON_FIELD = ContraSim.class.getDeclaredField("weapon");
            WEAPON_FIELD.setAccessible(true);   // Kotlin `var ... private set` ⇒ 无 public setter
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void forceWeapon(ContraSim sim, int kind) {
        try {
            WEAPON_FIELD.setInt(sim, kind);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        int max = args.length > 0 ? Integer.parseInt(args[0]) : 600000;
        int[] masks = {
                0,                                  // 第三轮现状
                NO_UP,                              // 只撤上瞄
                NO_DOWN,                            // 只撤蹲射
                NO_UP | NO_DOWN,                    // 撤两项瞄准
                NO_BOSS_LEFT,                       // 只撤 boss 后退
                JUMP_THROTTLE,                      // 只做跳跃节流
                NO_UP | NO_DOWN | JUMP_THROTTLE,
                WATCHDOG,                           // 只加推进看门狗
                NO_UP | NO_DOWN | WATCHDOG,
                FORCE_RESTART,
                NO_UP | NO_DOWN | NO_BOSS_LEFT | JUMP_THROTTLE,
                NO_LEFT | NO_UP | NO_DOWN,
                FORCE_WPN_R,                        // 关键对照：假设永远拿普通枪
                FORCE_WPN_R | NO_UP | NO_DOWN,
                FORCE_WPN_M,                        // 换成净增强的直线武器
                FORCE_WPN_M | NO_UP | NO_DOWN,
                FORCE_WPN_L,
                FORCE_WPN_L | NO_UP | NO_DOWN,
                FORCE_WPN_R | WATCHDOG,
        };
        System.out.printf("seed=2026 max=%d%n", max);
        System.out.println("mask  pass  topLv  step    deaths  stop    up      down    bossLv  deadScreens(top3)");
        for (int mask : masks) run(mask, max);
    }

    private static void run(int mask, int max) {
        ContraSim sim = new ContraSim(2026L);
        ContraBot bot = new ContraBot();
        GameInput in = new GameInput();
        int lastLevel = sim.getCurrentLevelNumber();
        int topLevel = lastLevel;
        int deaths = 0, stop = 0, upFrames = 0, downFrames = 0, bossLeft = 0;
        int[] deathScreens = new int[12];
        boolean wasAlive = true;
        int lastJumpStep = -999;
        int bestX = sim.getPlayerPxX(), sinceProgress = 0, recover = 0;
        boolean passed = false;
        int passStep = -1;

        for (int step = 0; step < max; step++) {
            if ((mask & FORCE_WPN_R) != 0) forceWeapon(sim, 0);
            else if ((mask & FORCE_WPN_M) != 0) forceWeapon(sim, 1);
            else if ((mask & FORCE_WPN_L) != 0) forceWeapon(sim, 4);
            bot.decide(sim, in);

            if ((mask & NO_UP) != 0) in.setUp(false);
            if ((mask & NO_DOWN) != 0) in.setDown(false);
            if ((mask & NO_BOSS_LEFT) != 0 && sim.getBossIsActive() && in.getLeft()) {
                bossLeft++;
                in.setLeft(false);
            }
            if ((mask & NO_LEFT) != 0) in.setLeft(false);
            if ((mask & JUMP_THROTTLE) != 0 && in.getJump()) {
                if (step - lastJumpStep < 18) in.setJump(false);
                else lastJumpStep = step;
            }

            // 推进看门狗：长时间不刷新最远 x ⇒ 强制回到「纯平射 + 右移」的基线行为
            if ((mask & (WATCHDOG | FORCE_RESTART)) != 0) {
                int x = sim.getPlayerPxX();
                if (x > bestX) { bestX = x; sinceProgress = 0; }
                else sinceProgress++;
                if (sinceProgress >= 240) {
                    sinceProgress = 0;
                    if ((mask & FORCE_RESTART) != 0) {
                        sim.restartLevel();
                        bestX = sim.getPlayerPxX();
                        recover = 0;
                    } else {
                        recover = 120;
                    }
                }
                if (recover > 0) {
                    recover--;
                    in.setUp(false);
                    in.setDown(false);
                    in.setLeft(false);
                    in.setRight(true);
                }
            }

            if (in.getUp()) upFrames++;
            if (in.getDown()) downFrames++;
            if (!in.getRight() && !in.getLeft()) stop++;

            boolean alive = sim.getPlayerIsAlive();
            if (wasAlive && !alive) {
                deaths++;
                int sc = sim.getPlayerPxX() / 256;
                if (sc >= 0 && sc < deathScreens.length) deathScreens[sc]++;
            }
            wasAlive = alive;

            sim.step(in);

            int lv = sim.getCurrentLevelNumber();
            if (lv != lastLevel) {
                lastLevel = lv;
                if (lv > topLevel) topLevel = lv;
                bestX = sim.getPlayerPxX();
                sinceProgress = 0;
                recover = 0;
                if (lv == 1 && topLevel >= 8) {
                    passed = true;
                    passStep = step + 1;
                    break;
                }
            }
        }
        System.out.printf("%4d  %-5s  %2d    %6d  %6d  %6d  %6d  %6d  %6d  %s%n",
                mask, passed ? "PASS" : "fail", topLevel,
                passed ? passStep : max, deaths, stop, upFrames, downFrames, bossLeft,
                topDeadScreens(deathScreens));
    }

    private static String topDeadScreens(int[] h) {
        int a = -1, b = -1, c = -1;
        for (int i = 0; i < h.length; i++) {
            if (a < 0 || h[i] > h[a]) { c = b; b = a; a = i; }
            else if (b < 0 || h[i] > h[b]) { c = b; b = i; }
            else if (c < 0 || h[i] > h[c]) { c = i; }
        }
        return String.format("s%d=%d s%d=%d s%d=%d",
                a, h[a < 0 ? 0 : a], b < 0 ? 0 : b, h[b < 0 ? 0 : b],
                c < 0 ? 0 : c, h[c < 0 ? 0 : c]);
    }
}
