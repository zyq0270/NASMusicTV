import com.nasmusic.tv.visualizer.arcade.GameInput;
import com.nasmusic.tv.visualizer.arcade.bot.ContraBot;
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim;

/** 离线快诊：直接跑已编译的 Kotlin 类，避免每轮等 Gradle。 */
public class E54Diag {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 2026L;
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 600000;
        ContraSim sim = new ContraSim(seed);
        ContraBot bot = new ContraBot();
        GameInput in = new GameInput();
        int lastLevel = sim.getCurrentLevelNumber();
        int maxX = sim.getPlayerPxX();
        int deaths = 0, upFrames = 0, jumpFrames = 0, downFrames = 0, stopFrames = 0;
        boolean wasAlive = true;
        for (int step = 0; step < max; step++) {
            bot.decide(sim, in);
            if (in.getUp()) upFrames++;
            if (in.getDown()) downFrames++;
            if (in.getJump()) jumpFrames++;
            if (!in.getRight() && !in.getLeft()) stopFrames++;
            sim.step(in);
            boolean alive = sim.getPlayerIsAlive();
            if (wasAlive && !alive) deaths++;
            wasAlive = alive;
            int lv = sim.getCurrentLevelNumber();
            int x = sim.getPlayerPxX();
            if (x > maxX) maxX = x;
            if (lv != lastLevel) {
                System.out.printf("step=%6d  LEVEL %d -> %d  x=%d  deaths=%d  up=%d  stop=%d%n",
                        step, lastLevel, lv, x, deaths, upFrames, stopFrames);
                lastLevel = lv;
                maxX = x;
            }
            if (step % 25000 == 0) {
                System.out.printf(
                    "t=%6d lv=%d x=%4d maxX=%4d y=%3d grnd=%-5s alive=%-5s lives=%d " +
                    "boss=%-5s bossHp=%2d wpn=%d score=%6d up=%6d jump=%5d down=%4d stop=%6d deaths=%d%n",
                    step, lv, x, maxX, sim.getPlayerPxY(), sim.getPlayerIsGrounded(), alive,
                    sim.getLives(), sim.getBossIsActive(), sim.getBossHealth(), sim.getWeapon(),
                    sim.getScore(), upFrames, jumpFrames, downFrames, stopFrames, deaths);
            }
        }
        System.out.printf("END level=%d x=%d maxX=%d deaths=%d up=%d stop=%d%n",
                lastLevel, sim.getPlayerPxX(), maxX, deaths, upFrames, stopFrames);
    }
}
