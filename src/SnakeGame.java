import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedList;
import java.util.Random;
import javax.swing.*;

public class SnakeGame extends JPanel implements ActionListener {

    //  Grid & timing
    private static final int COLS       = 25;
    private static final int ROWS       = 25;
    private static final int CELL       = 22;
    private static final int WIDTH      = COLS * CELL;
    private static final int HEIGHT     = ROWS * CELL;
    private static final int BASE_DELAY = 120;   // ms per tick (Normal)

    // ── Game state ─────────────────────────────────────────────────
    private final LinkedList<Point> snake = new LinkedList<>();
    private Point food;
    private Point dir     = new Point(1, 0);   // current direction
    private Point nextDir = new Point(1, 0);   // buffered next direction

    private int  score    = 0;
    private int  hiScore  = 0;
    private int  level    = 1;
    private int  eaten    = 0;          // food eaten this level
    private boolean running   = false;
    private boolean gameOver  = false;

    private Timer     timer;
    private final Random random = new Random();

    // ── Color palette ──────────────────────────────────────────────
    private static final Color BG_DARK   = new Color(10,  15, 10);
    private static final Color BG_LIGHT  = new Color(13,  19, 13);
    private static final Color FOOD_COL  = new Color(255, 70, 70);
    private static final Color HEAD_COL  = new Color(50, 230, 80);
    private static final Color EYE_COL   = Color.WHITE;
    private static final Color HUD_COL   = new Color(180, 220, 180);
    private static final Color OVER_BG   = new Color(0, 0, 0, 180);
    private static final Color OVER_RED  = new Color(255, 80, 80);
    private static final Color OVER_GRN  = new Color(60, 220, 90);
    private static final Color OVER_GRAY = new Color(170, 170, 170);

    // ──────────────────────────────────────────────────────────────
    public SnakeGame() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT + 40));  // +40 for HUD
        setBackground(BG_DARK);
        setFocusable(true);
        addKeyListener(new KeyHandler());
        showStartOverlay();
    }

    // ── Initialise / restart ───────────────────────────────────────
    private void initGame() {
        snake.clear();
        // Start with 4-segment snake in the middle
        int mx = COLS / 2, my = ROWS / 2;
        for (int i = 0; i < 4; i++) snake.add(new Point(mx - i, my));

        dir     = new Point(1, 0);
        nextDir = new Point(1, 0);
        score = 0; level = 1; eaten = 0;
        gameOver = false;
        placeFood();
    }

    // ── Food placement (never on snake) ───────────────────────────
    private void placeFood() {
        Point p;
        do { p = new Point(random.nextInt(COLS), random.nextInt(ROWS)); }
        while (snake.contains(p));
        food = p;
    }

    // ── Main game tick ────────────────────────────────────────────
    @Override
    public void actionPerformed(ActionEvent e) {
        if (!running) return;
        dir = new Point(nextDir.x, nextDir.y);    // apply buffered direction

        Point head = new Point(snake.getFirst().x + dir.x,
                snake.getFirst().y + dir.y);

        // Wall collision
        if (head.x < 0 || head.x >= COLS || head.y < 0 || head.y >= ROWS) {
            endGame(); return;
        }
        // Self collision
        if (snake.contains(head)) { endGame(); return; }

        snake.addFirst(head);

        if (head.equals(food)) {
            score++;
            eaten++;
            if (score > hiScore) hiScore = score;
            placeFood();
            // Level up every 5 food
            if (eaten % 5 == 0) {
                level++;
                eaten = 0;
                int newDelay = Math.max(40, timer.getDelay() - 8);
                timer.setDelay(newDelay);
            }
        } else {
            snake.removeLast();
        }
        repaint();
    }

    // ── Rendering ─────────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g);
        if (food != null) drawFood(g);
        drawSnake(g);
        drawHUD(g);
        if (!running) drawOverlay(g);
    }

    private void drawGrid(Graphics2D g) {
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                g.setColor((c + r) % 2 == 0 ? BG_DARK : BG_LIGHT);
                g.fillRect(c * CELL, r * CELL + 40, CELL, CELL);
            }
        }
    }

    private void drawFood(Graphics2D g) {
        int px = food.x * CELL + 2;
        int py = food.y * CELL + 40 + 2;
        int sz = CELL - 4;
        g.setColor(FOOD_COL);
        g.fillOval(px, py, sz, sz);
        // Shine
        g.setColor(new Color(255, 180, 180, 160));
        g.fillOval(px + sz/4, py + sz/5, sz/3, sz/4);
    }

    private void drawSnake(Graphics2D g) {
        int len = snake.size();
        for (int i = len - 1; i >= 0; i--) {
            Point s = snake.get(i);
            float t = (float) i / len;
            int gr = (int)(230 - t * 100);
            int rd = (int)(20  + t * 20);
            g.setColor(new Color(rd, gr, rd));

            int px = s.x * CELL + 1;
            int py = s.y * CELL + 40 + 1;
            int sz = CELL - 2;
            g.fill(new RoundRectangle2D.Float(px, py, sz, sz, 5, 5));

            // Head: draw eyes
            if (i == 0) {
                g.setColor(HEAD_COL);
                g.fill(new RoundRectangle2D.Float(px - 1, py - 1, sz + 2, sz + 2, 6, 6));
                drawEyes(g, s.x * CELL, s.y * CELL + 40);
            }
        }
    }

    private void drawEyes(Graphics2D g, int ox, int oy) {
        // Eye positions shift based on direction
        int ex1, ey1, ex2, ey2;
        if (dir.x == 1) {           // right
            ex1 = ox + CELL - 6; ey1 = oy + 4;
            ex2 = ox + CELL - 6; ey2 = oy + CELL - 8;
        } else if (dir.x == -1) {   // left
            ex1 = ox + 3;        ey1 = oy + 4;
            ex2 = ox + 3;        ey2 = oy + CELL - 8;
        } else if (dir.y == -1) {   // up
            ex1 = ox + 4;        ey1 = oy + 3;
            ex2 = ox + CELL - 8; ey2 = oy + 3;
        } else {                    // down
            ex1 = ox + 4;        ey1 = oy + CELL - 6;
            ex2 = ox + CELL - 8; ey2 = oy + CELL - 6;
        }
        g.setColor(EYE_COL);
        g.fillOval(ex1, ey1, 4, 4);
        g.fillOval(ex2, ey2, 4, 4);
        g.setColor(Color.BLACK);
        g.fillOval(ex1 + 1, ey1 + 1, 2, 2);
        g.fillOval(ex2 + 1, ey2 + 1, 2, 2);
    }

    private void drawHUD(Graphics2D g) {
        g.setColor(new Color(18, 26, 18));
        g.fillRect(0, 0, WIDTH, 40);
        g.setColor(new Color(30, 50, 30));
        g.drawLine(0, 39, WIDTH, 39);

        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        g.setColor(HUD_COL);
        g.drawString("SCORE  " + score,      10,  26);
        g.drawString("BEST   " + hiScore, WIDTH/2 - 50, 26);
        g.drawString("LVL  "  + level,   WIDTH - 80,    26);
    }

    private void drawOverlay(Graphics2D g) {
        g.setColor(OVER_BG);
        g.fillRect(0, 40, WIDTH, HEIGHT);

        if (gameOver) {
            drawCenteredString(g, "GAME OVER", new Font("Monospaced", Font.BOLD, 36),
                    OVER_RED,    WIDTH / 2, HEIGHT / 2 - 10 + 40);
            drawCenteredString(g, "Score: " + score + "   Level: " + level,
                    new Font("Monospaced", Font.PLAIN, 15),
                    OVER_GRAY,  WIDTH / 2, HEIGHT / 2 + 32 + 40);
            drawCenteredString(g, "[ ENTER / SPACE ] to play again",
                    new Font("Monospaced", Font.PLAIN, 13),
                    OVER_GRN,   WIDTH / 2, HEIGHT / 2 + 60 + 40);
        } else {
            drawCenteredString(g, "SNAKE",
                    new Font("Monospaced", Font.BOLD, 48),
                    OVER_GRN,   WIDTH / 2, HEIGHT / 2 - 10 + 40);
            drawCenteredString(g, "Arrow Keys or WASD to move",
                    new Font("Monospaced", Font.PLAIN, 13),
                    OVER_GRAY,  WIDTH / 2, HEIGHT / 2 + 30 + 40);
            drawCenteredString(g, "[ ENTER / SPACE ] to start",
                    new Font("Monospaced", Font.PLAIN, 13),
                    OVER_GRN,   WIDTH / 2, HEIGHT / 2 + 55 + 40);
        }
    }

    private void drawCenteredString(Graphics2D g, String s, Font f,
                                    Color c, int cx, int cy) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy);
    }

    // ── State transitions ─────────────────────────────────────────
    private void showStartOverlay() {
        running = false;
        gameOver = false;
        repaint();
    }

    private void startGame() {
        initGame();
        running = true;
        if (timer != null) timer.stop();
        timer = new Timer(BASE_DELAY, this);
        timer.start();
    }

    private void endGame() {
        running = false;
        gameOver = true;
        timer.stop();
        repaint();
    }

    // ── Input ─────────────────────────────────────────────────────
    private class KeyHandler extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            int k = e.getKeyCode();

            // Start / restart
            if (!running && (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE)) {
                startGame();
                return;
            }

            // Direction buffering — forbid 180-degree reversal
            switch (k) {
                case KeyEvent.VK_LEFT,  KeyEvent.VK_A -> { if (dir.x != 1)  nextDir.setLocation(-1,  0); }
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> { if (dir.x != -1) nextDir.setLocation( 1,  0); }
                case KeyEvent.VK_UP,    KeyEvent.VK_W -> { if (dir.y != 1)  nextDir.setLocation( 0, -1); }
                case KeyEvent.VK_DOWN,  KeyEvent.VK_S -> { if (dir.y != -1) nextDir.setLocation( 0,  1); }
            }
        }
    }

    // ── Entry point ───────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Snake");
            SnakeGame game = new SnakeGame();
            frame.add(game);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}