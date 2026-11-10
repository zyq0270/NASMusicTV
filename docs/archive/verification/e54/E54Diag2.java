import com.nasmusic.tv.visualizer.arcade.GameInput;
import com.nasmusic.tv.visualizer.arcade.bot.ContraBot;
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim;

/** 验证假设：boss 战禁用 left（保住 facing=1）+ 禁用 down（消除减速停步）。 */
public class E54Diag2 {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 2026L;
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 600000;
        ContraSim sim = new ContraSim(seed);
        ContraBot bot = new ContraBot();
        GameInput in = new GameInput();
        int lastLevel = sim.getCurrentLevelNumber();
        int deaths = 0, stopFrames = 0, downFrames = 0, leftBoss = 0;
        boolean wasAlive = true;
        for (int step = 0; step < max; step++) {
            bot.decide(sim, in);
            // === 拟议修复（先验证，再落到 Kotlin） ===
            if (sim.getBossIsActive() && in.getLeft()) leftBoss++;
            if (sim.getBossIsActive()) in.setLeft(false);
            if (in.getDown()) downFrames++;
            in.setDown(false);
            if (!in.getRight() && !in.getLeft()) stopFrames++;
            sim.step(in);
            boolean alive = sim.getPlayerIsAlive();
            if (wasAlive && !alive) deaths++;
            wasAlive = alive;
            int lv = sim.getCurrentLevelNumber();
            if (lv != lastLevel) {
                System.out.printf("step=%6d  LEVEL %d -> %d  x=%d  deaths=%d  stop=%d%n",
                        step, lastLevel, lv, sim.getPlayerPxX(), deaths, stopFrames);
                lastLevel = lv;
                if (lv == 1) {
                    System.out.printf(">>> 8 关全部走完并回到第 1 关：step=%d deaths=%d stop=%d bossLeft=%d%n",
                            step, deaths, stopFrames, leftBoss);
                    return;
                }
            }
            if (step % 100000 == 0) {
                System.out.printf("t=%6d lv=%d x=%4d alive=%-5s lives=%d boss=%-5s bossHp=%2d wpn=%d deaths=%d stop=%d%n",
                        step, lv, sim.getPlayerPxX(), alive, sim.getLives(), sim.getBossIsActive(),
                        sim.getBossHealth(), sim.getWeapon(), deaths, stopFrames);
            }
        }
        System.out.printf("END level=%d x=%d deaths=%d stop=%d bossLeft=%d%n",
                lastLevel, sim.getPlayerPxX(), deaths, stopFrames, leftBoss);
    }
}
