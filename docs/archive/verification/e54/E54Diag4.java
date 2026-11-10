import com.nasmusic.tv.visualizer.arcade.GameInput;
import com.nasmusic.tv.visualizer.arcade.bot.ContraBot;
import com.nasmusic.tv.visualizer.arcade.sim.ContraConst;
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim;

/**
 * 第四轮 bot 优化前的死亡画像：每关死亡数 / 死亡原因推断 / 死亡时输入与站姿 /
 * 存活时长 / boss 战时长 / 停步占比。只读统计，不改行为。
 */
public class E54Diag4 {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 2026L;
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 600000;

        ContraSim sim = new ContraSim(seed);
        ContraBot bot = new ContraBot();
        GameInput in = new GameInput();

        int[] deathByLevel = new int[9];
        int[] deathByCause = new int[6]; // 0 water 1 bullet 2 contact 3 fall 4 boss 5 unknown
        int[] causeWater = {0}, causeBullet = {0}, causeContact = {0}, causeFall = {0}, causeBoss = {0}, causeUnknown = {0};
        int deaths = 0;
        int aliveFrames = 0, totalLifeFrames = 0;
        int stopFrames = 0, jumpFrames = 0, upFrames = 0, downFrames = 0;
        int bossFrames = 0, bossKills = 0;
        int level = sim.getCurrentLevelNumber();
        int lifeStart = 0;
        boolean wasAlive = true;
        boolean prevJump = false, prevUp = false, prevDown = false;
        int lastLv = level;
        int bossStart = -1;

        for (int step = 0; step < max; step++) {
            bot.decide(sim, in);
            prevJump = in.getJump();
            prevUp = in.getUp();
            prevDown = in.getDown();
            if (sim.getBossIsActive()) {
                if (bossStart < 0) bossStart = step;
                bossFrames++;
            }
            sim.step(in);

            boolean alive = sim.getPlayerIsAlive();
            if (alive) {
                aliveFrames++;
                if (prevJump) jumpFrames++;
                if (prevUp) upFrames++;
                if (prevDown) downFrames++;
                if (!in.getRight() && !in.getLeft()) stopFrames++;
            }

            // boss 击杀（level 切换前 bossActive 从 true 变 false）
            if (bossStart >= 0 && !sim.getBossIsActive() && alive) {
                bossKills++;
                bossStart = -1;
            }

            int lv = sim.getCurrentLevelNumber();
            if (lv != lastLv) {
                lastLv = lv;
                bossStart = -1;
            }

            if (wasAlive && !alive) {
                deaths++;
                int lvNow = sim.getCurrentLevelNumber();
                if (lvNow >= 1 && lvNow <= 8) deathByLevel[lvNow]++;
                totalLifeFrames += aliveFrames - lifeStart;
                lifeStart = aliveFrames;

                int px = sim.getPlayerPxX(), py = sim.getPlayerPxY();
                int feetRow = (py + ContraConst.PLAYER_H - 1) / ContraConst.TILE;
                int col = px / ContraConst.TILE;
                int feetCode = sim.terrainCode(col, feetRow);
                int midCode = sim.terrainCode(col, (py + ContraConst.PLAYER_H / 2) / ContraConst.TILE);
                boolean water = feetCode == ContraConst.C_WATER || midCode == ContraConst.C_WATER;
                boolean fell = py > ContraConst.LOGICAL_H + 32;
                boolean bullet = nearBullet(sim, px, py);
                boolean contact = nearEnemy(sim, px, py, 26);
                boolean bossTouch = sim.getBossIsActive() && Math.abs(sim.getBossPxX() - px) < 40;

                int cause;
                if (fell) { cause = 3; causeFall[0]++; }
                else if (water) { cause = 0; causeWater[0]++; }
                else if (bullet) { cause = 1; causeBullet[0]++; }
                else if (bossTouch) { cause = 4; causeBoss[0]++; }
                else if (contact) { cause = 2; causeContact[0]++; }
                else { cause = 5; causeUnknown[0]++; }
                deathByCause[cause]++;
            }
            if (!wasAlive && alive) {
                lifeStart = aliveFrames;
            }
            wasAlive = alive;
        }

        System.out.printf("seed=%d steps=%d deaths=%d%n", seed, max, deaths);
        System.out.println("每关死亡: " + arr(deathByLevel, 1, 8));
        System.out.printf("死因: water=%d bullet=%d contact=%d fall=%d boss=%d unknown=%d%n",
                causeWater[0], causeBullet[0], causeContact[0], causeFall[0], causeBoss[0], causeUnknown[0]);
        System.out.printf("行为帧占比(存活帧=%d): stop=%.1f%% jump=%.1f%% up=%.1f%% down=%.1f%%%n",
                aliveFrames, 100.0 * stopFrames / Math.max(1, aliveFrames),
                100.0 * jumpFrames / Math.max(1, aliveFrames),
                100.0 * upFrames / Math.max(1, aliveFrames),
                100.0 * downFrames / Math.max(1, aliveFrames));
        System.out.printf("平均存活 %.0f 帧（≈%.1f 秒）; bossFrames=%d bossKills=%d%n",
                (double) totalLifeFrames / Math.max(1, deaths),
                (double) totalLifeFrames / Math.max(1, deaths) / 60.0,
                bossFrames, bossKills);
    }

    private static boolean nearBullet(ContraSim sim, int px, int py) {
        for (int i = 0; i < sim.getEnemyBulletSlots(); i++) {
            if (!sim.enemyBulletActive(i)) continue;
            int dx = sim.enemyBulletPxX(i) - px;
            int dy = sim.enemyBulletPxY(i) - py;
            if (dx > -20 && dx < 30 && dy > -26 && dy < 30) return true;
        }
        return false;
    }

    private static boolean nearEnemy(ContraSim sim, int px, int py, int r) {
        for (int i = 0; i < sim.getEnemySlots(); i++) {
            if (!sim.enemyActive(i)) continue;
            if (sim.enemyTypeOf(i) == ContraConst.E_ITEM) continue;
            int dx = sim.enemyPxX(i) - px;
            int dy = sim.enemyPxY(i) - py;
            if (dx > -r && dx < r && dy > -r && dy < r) return true;
        }
        return false;
    }

    private static String arr(int[] a, int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = from; i <= to; i++) sb.append(i).append('=').append(a[i]).append(i < to ? " " : "");
        return sb.append("]").toString();
    }
}
