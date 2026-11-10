import com.nasmusic.tv.visualizer.arcade.GameInput;
import com.nasmusic.tv.visualizer.arcade.bot.ContraBot;
import com.nasmusic.tv.visualizer.arcade.sim.ContraConst;
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim;

/** 专查第 5 关（熔岩）死亡明细：死亡瞬间的坐标 / 地形码 / 附近敌人与弹幕 / 上一步输入。 */
public class E54Diag5 {

    public static void main(String[] args) {
        long seed = 2026L;
        int max = args.length > 0 ? Integer.parseInt(args[0]) : 600000;
        int detail = args.length > 1 ? Integer.parseInt(args[1]) : 40;

        ContraSim sim = new ContraSim(seed);
        ContraBot bot = new ContraBot();
        GameInput in = new GameInput();

        int deaths = 0;
        boolean wasAlive = true;
        boolean prevJump = false, prevUp = false, prevDown = false, prevRight = true;
        int jumpHold = 0;
        int lastLv = sim.getCurrentLevelNumber();

        for (int step = 0; step < max && deaths < detail; step++) {
            bot.decide(sim, in);
            prevJump = in.getJump();
            prevUp = in.getUp();
            prevDown = in.getDown();
            prevRight = in.getRight() && !in.getLeft();
            if (in.getJump()) jumpHold = 12;
            if (jumpHold > 0) jumpHold--;
            sim.step(in);

            boolean alive = sim.getPlayerIsAlive();
            if (wasAlive && !alive) {
                deaths++;
                int lv = sim.getCurrentLevelNumber();
                int px = sim.getPlayerPxX(), py = sim.getPlayerPxY();
                int feetRow = (py + ContraConst.PLAYER_H - 1) / ContraConst.TILE;
                int col = px / ContraConst.TILE;
                int cFeet = sim.terrainCode(col, feetRow);
                int cMid = sim.terrainCode(col, (py + ContraConst.PLAYER_H / 2) / ContraConst.TILE);
                int cAhead = sim.terrainCode(col + 1, feetRow);
                StringBuilder enemies = new StringBuilder();
                int bullets = 0;
                for (int i = 0; i < sim.getEnemySlots(); i++) {
                    if (!sim.enemyActive(i)) continue;
                    int t = sim.enemyTypeOf(i);
                    int dx = sim.enemyPxX(i) - px;
                    int dy = sim.enemyPxY(i) - py;
                    if (Math.abs(dx) < 48 && Math.abs(dy) < 48) {
                        enemies.append("t").append(t).append("@").append(dx).append(",").append(dy).append(" ");
                    }
                }
                for (int i = 0; i < sim.getEnemyBulletSlots(); i++) {
                    if (!sim.enemyBulletActive(i)) continue;
                    int dx = sim.enemyBulletPxX(i) - px;
                    int dy = sim.enemyBulletPxY(i) - py;
                    if (dx > -24 && dx < 32 && dy > -28 && dy < 32) bullets++;
                }
                if (lv == 5) {
                    System.out.printf("d%03d step=%d x=%d y=%d feetR=%d code(feet,mid,ahead)=%d,%d,%d in(J=%B U=%B D=%B R=%B jh=%d) bullets=%d%n        enemies: %s%n",
                            deaths, step, px, py, feetRow, cFeet, cMid, cAhead,
                            prevJump, prevUp, prevDown, prevRight, jumpHold, bullets, enemies);
                }
            }
            wasAlive = alive;
            int lv = sim.getCurrentLevelNumber();
            if (lv != lastLv) lastLv = lv;
        }
        System.out.println("deaths(截取)=" + deaths);
    }
}
