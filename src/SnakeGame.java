import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

public class SnakeGame extends JPanel implements ActionListener {

    private static final int COLS = 25;
    private static final int ROWS = 25;
    private static final int CELL = 22;
    private static final int WIDTH = COLS * CELL;
    private static final int HEIGHT = ROWS * CELL;
    private static final int HUD_H = 44;
    private static final int MIN_DELAY = 36;
    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), ".snakegame");
    private static final Path SCORES_PATH = DATA_DIR.resolve("scores.txt");
    private static final Path SETTINGS_PATH = DATA_DIR.resolve("settings.properties");
    private static final DateTimeFormatter WHEN_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    enum Difficulty {
        EASY(162),
        MEDIUM(112),
        HARD(74);
        final int baseDelay;
        Difficulty(int baseDelay) { this.baseDelay = baseDelay; }
    }

    enum ObstacleMode {
        NONE("Off"),
        LIGHT("Light"),
        HEAVY("Heavy");
        final String label;
        ObstacleMode(String label) { this.label = label; }
    }

    enum Theme {
        FOREST(
                new Color(8, 14, 10), new Color(14, 22, 16),
                new Color(48, 235, 95), new Color(18, 110, 52), new Color(255, 82, 92),
                new Color(185, 228, 195), new Color(62, 48, 38), new Color(34, 58, 40)),
        OCEAN(
                new Color(6, 16, 32), new Color(10, 28, 52),
                new Color(72, 210, 255), new Color(28, 98, 178), new Color(255, 196, 72),
                new Color(176, 210, 240), new Color(48, 62, 82), new Color(28, 72, 118)),
        SUNSET(
                new Color(26, 10, 20), new Color(42, 16, 34),
                new Color(255, 118, 72), new Color(190, 48, 132), new Color(255, 236, 108),
                new Color(245, 188, 210), new Color(72, 38, 34), new Color(120, 40, 72)),
        MONO(
                new Color(18, 18, 20), new Color(28, 28, 32),
                new Color(228, 228, 236), new Color(110, 112, 122), new Color(255, 88, 88),
                new Color(198, 200, 208), new Color(56, 56, 60), new Color(90, 90, 98)),
        NEON(
                new Color(14, 8, 28), new Color(24, 12, 42),
                new Color(0, 245, 212), new Color(168, 72, 255), new Color(255, 105, 180),
                new Color(200, 190, 255), new Color(52, 36, 72), new Color(110, 60, 200)),
        SLATE(
                new Color(16, 20, 28), new Color(24, 30, 42),
                new Color(120, 200, 255), new Color(52, 88, 140), new Color(255, 160, 96),
                new Color(200, 212, 230), new Color(48, 52, 62), new Color(64, 92, 128));
        final Color bg0, bg1, head, bodyDeep, food, hud, obstacle, hudAccent;
        Theme(Color bg0, Color bg1, Color head, Color bodyDeep, Color food, Color hud, Color obstacle, Color hudAccent) {
            this.bg0 = bg0; this.bg1 = bg1; this.head = head; this.bodyDeep = bodyDeep;
            this.food = food; this.hud = hud; this.obstacle = obstacle; this.hudAccent = hudAccent;
        }
    }

    private static final Color EYE_W = new Color(248, 252, 255);
    private static final Color OVER_BG = new Color(0, 0, 0, 188);
    private static final Color OVER_RED = new Color(255, 88, 88);
    private static final Color OVER_GRN = new Color(72, 230, 118);
    private static final Color OVER_GRAY = new Color(175, 180, 190);

    private final LinkedList<Point> snake = new LinkedList<>();
    private final HashSet<Point> obstacles = new HashSet<>();
    private Point food;
    private Point dir = new Point(1, 0);
    private Point nextDir = new Point(1, 0);

    private int score;
    private int hiScore;
    private int displayLevel;
    private boolean running;
    private boolean gameOver;

    private javax.swing.Timer timer;
    private final Random random = new Random();

    private Difficulty difficulty = Difficulty.MEDIUM;
    private ObstacleMode obstacleMode = ObstacleMode.LIGHT;
    private boolean wrapEdges = false;
    private Theme theme = Theme.FOREST;
    private String playerName = "Player";
    private boolean snakeUseTheme = true;
    private Color snakeHeadCustom = new Color(50, 230, 80);
    private Color snakeTailCustom = new Color(20, 140, 45);

    private JFrame hostFrame;

    public SnakeGame() {
        loadSettings();
        setPreferredSize(new Dimension(WIDTH, HEIGHT + HUD_H));
        setBackground(theme.bg0);
        setFocusable(true);
        addKeyListener(new KeyHandler());
        showStartOverlay();
    }

    void attachMenus(JFrame frame) {
        this.hostFrame = frame;
        frame.setTitle("Snake — " + playerName);
        JMenuBar bar = new JMenuBar();
        JMenu mGame = new JMenu("Game");
        JMenuItem miSet = new JMenuItem("Settings…");
        miSet.setAccelerator(KeyStroke.getKeyStroke("control COMMA"));
        miSet.addActionListener(e -> showSettingsDialog(frame));
        JMenuItem miLb = new JMenuItem("Leaderboard");
        miLb.addActionListener(e -> showLeaderboardDialog(frame));
        JMenuItem miEx = new JMenuItem("Quit");
        miEx.addActionListener(e -> System.exit(0));
        mGame.add(miSet);
        mGame.add(miLb);
        mGame.addSeparator();
        mGame.add(miEx);
        bar.add(mGame);
        frame.setJMenuBar(bar);
    }

    private void loadSettings() {
        Properties p = new Properties();
        if (!Files.isRegularFile(SETTINGS_PATH)) return;
        try (InputStream in = Files.newInputStream(SETTINGS_PATH)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        playerName = sanitizeName(p.getProperty("playerName", playerName));
        try { difficulty = Difficulty.valueOf(p.getProperty("difficulty", difficulty.name())); } catch (IllegalArgumentException ignored) { }
        try { obstacleMode = ObstacleMode.valueOf(p.getProperty("obstacleMode", obstacleMode.name())); } catch (IllegalArgumentException ignored) { }
        try { theme = Theme.valueOf(p.getProperty("theme", theme.name())); } catch (IllegalArgumentException ignored) { }
        wrapEdges = Boolean.parseBoolean(p.getProperty("wrapEdges", String.valueOf(wrapEdges)));
        snakeUseTheme = Boolean.parseBoolean(p.getProperty("snakeUseTheme", String.valueOf(snakeUseTheme)));
        snakeHeadCustom = parseColor(p.getProperty("snakeHead"), snakeHeadCustom);
        snakeTailCustom = parseColor(p.getProperty("snakeTail"), snakeTailCustom);
    }

    private void saveSettings() {
        Properties p = new Properties();
        p.setProperty("playerName", playerName);
        p.setProperty("difficulty", difficulty.name());
        p.setProperty("obstacleMode", obstacleMode.name());
        p.setProperty("theme", theme.name());
        p.setProperty("wrapEdges", String.valueOf(wrapEdges));
        p.setProperty("snakeUseTheme", String.valueOf(snakeUseTheme));
        p.setProperty("snakeHead", colorToString(snakeHeadCustom));
        p.setProperty("snakeTail", colorToString(snakeTailCustom));
        try {
            Files.createDirectories(DATA_DIR);
            try (OutputStream out = Files.newOutputStream(SETTINGS_PATH)) {
                p.store(out, "SnakeGame");
            }
        } catch (IOException ignored) { }
    }

    private static Color parseColor(String s, Color dflt) {
        if (s == null || s.isBlank()) return dflt;
        try {
            int v = Integer.parseUnsignedInt(s, 16);
            return new Color((v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String colorToString(Color c) {
        return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void showSettingsDialog(JFrame owner) {
        JDialog d = new JDialog(owner, "Game settings", true);
        d.setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 14, 8, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;

        JTextField nameF = new JTextField(playerName, 18);
        JComboBox<Difficulty> diffC = new JComboBox<>(Difficulty.values());
        diffC.setSelectedItem(difficulty);
        JComboBox<ObstacleMode> obsC = new JComboBox<>(ObstacleMode.values());
        obsC.setSelectedItem(obstacleMode);
        JRadioButton wrapB = new JRadioButton("Wrap (exit one side, enter the other)", wrapEdges);
        JRadioButton wallB = new JRadioButton("Solid walls (game over)", !wrapEdges);
        ButtonGroup wg = new ButtonGroup();
        wg.add(wrapB);
        wg.add(wallB);
        JComboBox<Theme> thC = new JComboBox<>(Theme.values());
        thC.setSelectedItem(theme);
        thC.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean foc) {
                JLabel lb = (JLabel) super.getListCellRendererComponent(list, v, i, sel, foc);
                if (v instanceof Theme t) {
                    lb.setText(cap(t.name()));
                    lb.setIcon(colorSwatch(t.head, t.bg0));
                }
                return lb;
            }
        });
        JCheckBox useThemeSnake = new JCheckBox("Use theme colors for snake", snakeUseTheme);
        JButton pickHead = new JButton("Head…");
        JButton pickTail = new JButton("Tail…");
        JLabel headPrev = new JLabel(colorSwatch(snakeHeadCustom, Color.DARK_GRAY));
        JLabel tailPrev = new JLabel(colorSwatch(snakeTailCustom, Color.DARK_GRAY));
        pickHead.setEnabled(!snakeUseTheme);
        pickTail.setEnabled(!snakeUseTheme);

        Runnable refreshPick = () -> {
            headPrev.setIcon(colorSwatch(snakeHeadCustom, Color.DARK_GRAY));
            tailPrev.setIcon(colorSwatch(snakeTailCustom, Color.DARK_GRAY));
        };

        useThemeSnake.addActionListener(ev -> {
            boolean on = useThemeSnake.isSelected();
            pickHead.setEnabled(!on);
            pickTail.setEnabled(!on);
        });
        pickHead.addActionListener(ev -> {
            Color col = JColorChooser.showDialog(d, "Snake head", snakeHeadCustom);
            if (col != null) { snakeHeadCustom = col; useThemeSnake.setSelected(false); pickHead.setEnabled(true); pickTail.setEnabled(true); refreshPick.run(); }
        });
        pickTail.addActionListener(ev -> {
            Color col = JColorChooser.showDialog(d, "Snake tail", snakeTailCustom);
            if (col != null) { snakeTailCustom = col; useThemeSnake.setSelected(false); pickHead.setEnabled(true); pickTail.setEnabled(true); refreshPick.run(); }
        });

        c.gridwidth = 1;
        form.add(new JLabel("Name"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        form.add(nameF, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        form.add(new JLabel("Speed (difficulty)"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        form.add(diffC, c);

        c.gridy++;
        c.gridx = 0;
        form.add(new JLabel("Obstacles"), c);
        c.gridx = 1;
        form.add(obsC, c);
        c.gridx = 2;
        form.add(new JLabel("<html><small>Independent of speed</small></html>"), c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        form.add(new JLabel("Walls"), c);
        c.gridy++;
        form.add(wrapB, c);
        c.gridy++;
        form.add(wallB, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        form.add(new JLabel("Board theme"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        form.add(thC, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        form.add(useThemeSnake, c);
        c.gridy++;
        JPanel snakeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        snakeRow.add(new JLabel("Custom snake"));
        snakeRow.add(pickHead);
        snakeRow.add(headPrev);
        snakeRow.add(pickTail);
        snakeRow.add(tailPrev);
        form.add(snakeRow, c);

        d.add(form, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        south.add(cancel);
        south.add(ok);
        d.add(south, BorderLayout.SOUTH);

        cancel.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            playerName = sanitizeName(nameF.getText());
            difficulty = (Difficulty) diffC.getSelectedItem();
            obstacleMode = (ObstacleMode) obsC.getSelectedItem();
            theme = (Theme) thC.getSelectedItem();
            wrapEdges = wrapB.isSelected();
            snakeUseTheme = useThemeSnake.isSelected();
            saveSettings();
            if (hostFrame != null) {
                hostFrame.setTitle("Snake — " + playerName);
            }
            setBackground(theme.bg0);
            if (!running) repaint();
            d.dispose();
        });

        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    private static Icon colorSwatch(Color fill, Color border) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setColor(fill);
                g2.fillRoundRect(x, y, 18, 12, 4, 4);
                g2.setColor(border);
                g2.drawRoundRect(x, y, 18, 12, 4, 4);
            }
            @Override public int getIconWidth() { return 18; }
            @Override public int getIconHeight() { return 12; }
        };
    }

    private static String sanitizeName(String s) {
        String t = s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return t.isEmpty() ? "Player" : t.substring(0, Math.min(24, t.length()));
    }

    private int tickDelay() {
        int n = snake.size();
        int shave = Math.min(74, (n - 1) * 3);
        return Math.max(MIN_DELAY, difficulty.baseDelay - shave);
    }

    private Color headColor() { return snakeUseTheme ? theme.head : snakeHeadCustom; }
    private Color tailColor() { return snakeUseTheme ? theme.bodyDeep : snakeTailCustom; }

    private void initGame() {
        snake.clear();
        int mx = COLS / 2, my = ROWS / 2;
        for (int i = 0; i < 4; i++) snake.add(new Point(mx - i, my));

        dir = new Point(1, 0);
        nextDir = new Point(1, 0);
        score = 0;
        displayLevel = 1;
        gameOver = false;
        buildObstacles();
        placeFood();
    }

    private void buildObstacles() {
        obstacles.clear();
        if (obstacleMode == ObstacleMode.NONE) return;

        int count = obstacleMode == ObstacleMode.LIGHT ? 12 : 26;
        int margin = 3;
        for (int i = 0; i < count; ) {
            Point p = new Point(random.nextInt(COLS), random.nextInt(ROWS));
            if (inStartZone(p)) continue;
            if (obstacles.add(p)) i++;
        }
        if (obstacleMode == ObstacleMode.HEAVY) {
            for (int cc = margin; cc < COLS - margin; cc += 2) {
                Point a = new Point(cc, margin);
                Point b = new Point(cc, ROWS - 1 - margin);
                if (!inStartZone(a)) obstacles.add(a);
                if (!inStartZone(b)) obstacles.add(b);
            }
            for (int r = margin + 1; r < ROWS - margin - 1; r += 2) {
                Point a = new Point(margin, r);
                Point b = new Point(COLS - 1 - margin, r);
                if (!inStartZone(a)) obstacles.add(a);
                if (!inStartZone(b)) obstacles.add(b);
            }
        }
    }

    private boolean inStartZone(Point p) {
        int mx = COLS / 2, my = ROWS / 2;
        return Math.abs(p.x - mx) <= 3 && Math.abs(p.y - my) <= 3;
    }

    private void placeFood() {
        Point p;
        int guard = 0;
        do {
            p = new Point(random.nextInt(COLS), random.nextInt(ROWS));
            if (++guard > 5000) break;
        } while (snake.contains(p) || obstacles.contains(p));
        food = p;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!running) return;
        dir = new Point(nextDir.x, nextDir.y);

        Point head = new Point(snake.getFirst().x + dir.x, snake.getFirst().y + dir.y);

        if (wrapEdges) {
            if (head.x < 0) head.x = COLS - 1;
            else if (head.x >= COLS) head.x = 0;
            if (head.y < 0) head.y = ROWS - 1;
            else if (head.y >= ROWS) head.y = 0;
        } else {
            if (head.x < 0 || head.x >= COLS || head.y < 0 || head.y >= ROWS) {
                endGame();
                return;
            }
        }

        if (obstacles.contains(head) || snake.contains(head)) {
            endGame();
            return;
        }

        snake.addFirst(head);

        if (head.equals(food)) {
            score++;
            if (score > hiScore) hiScore = score;
            displayLevel = 1 + score / 5;
            placeFood();
        } else {
            snake.removeLast();
        }

        int td = tickDelay();
        if (timer.getDelay() != td) timer.setDelay(td);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        drawGrid(g);
        drawObstacles(g);
        if (food != null) drawFood(g);
        drawSnake(g);
        drawHUD(g);
        if (!running) drawOverlay(g);
    }

    private void drawGrid(Graphics2D g) {
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                Color base = (c + r) % 2 == 0 ? theme.bg0 : theme.bg1;
                int x = c * CELL;
                int y = r * CELL + HUD_H;
                g.setColor(base);
                g.fillRect(x, y, CELL, CELL);
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 40));
                g.drawRect(x, y, CELL - 1, CELL - 1);
            }
        }
    }

    private void drawObstacles(Graphics2D g) {
        Color base = theme.obstacle;
        Color hi = brighten(base, 1.18f);
        Color lo = darken(base, 0.72f);
        for (Point o : obstacles) {
            int px = o.x * CELL + 1;
            int py = o.y * CELL + HUD_H + 1;
            int w = CELL - 2;
            int h = CELL - 2;
            g.setPaint(new GradientPaint(px, py, hi, px + w, py + h, lo));
            g.fillRoundRect(px, py, w, h, 5, 5);
            g.setColor(new Color(0, 0, 0, 90));
            g.drawRoundRect(px, py, w, h, 5, 5);
        }
    }

    private static Color brighten(Color c, float f) {
        return new Color(clamp(c.getRed() * f), clamp(c.getGreen() * f), clamp(c.getBlue() * f));
    }

    private static Color darken(Color c, float f) {
        return new Color(clamp(c.getRed() * f), clamp(c.getGreen() * f), clamp(c.getBlue() * f));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private void drawFood(Graphics2D g) {
        int px = food.x * CELL + 2;
        int py = food.y * CELL + HUD_H + 2;
        int sz = CELL - 4;
        Color f = theme.food;
        g.setPaint(new RadialGradientPaint(px + sz * 0.35f, py + sz * 0.35f, sz * 0.9f,
                new float[]{0f, 1f},
                new Color[]{brighten(f, 1.25f), darken(f, 0.55f)}));
        g.fillOval(px, py, sz, sz);
        g.setColor(new Color(255, 255, 255, 110));
        g.fillOval(px + sz / 5, py + sz / 6, sz / 3, sz / 4);
    }

    private void drawSnake(Graphics2D g) {
        Color hC = headColor();
        Color tC = tailColor();
        int len = snake.size();
        for (int i = len - 1; i >= 0; i--) {
            Point s = snake.get(i);
            float t = len > 1 ? (float) i / (len - 1) : 0f;
            int r = (int) (hC.getRed() * (1 - t) + tC.getRed() * t);
            int gr = (int) (hC.getGreen() * (1 - t) + tC.getGreen() * t);
            int b = (int) (hC.getBlue() * (1 - t) + tC.getBlue() * t);
            Color mid = new Color(r, gr, b);
            Color top = brighten(mid, 1.12f);
            Color bot = darken(mid, 0.78f);

            int px = s.x * CELL + 1;
            int py = s.y * CELL + HUD_H + 1;
            int sz = CELL - 2;
            g.setPaint(new GradientPaint(px, py, top, px + sz, py + sz, bot));
            g.fill(new RoundRectangle2D.Float(px, py, sz, sz, 7, 7));
            g.setColor(new Color(255, 255, 255, i == 0 ? 55 : 28));
            g.fill(new RoundRectangle2D.Float(px + 2, py + 2, sz * 0.45f, sz * 0.35f, 4, 4));
            g.setColor(new Color(0, 0, 0, 70));
            g.draw(new RoundRectangle2D.Float(px, py, sz, sz, 7, 7));

            if (i == 0) {
                Color hh = brighten(hC, 1.08f);
                Color hd = darken(hC, 0.62f);
                g.setPaint(new GradientPaint(px - 1, py - 1, hh, px + sz + 1, py + sz + 1, hd));
                g.fill(new RoundRectangle2D.Float(px - 1, py - 1, sz + 2, sz + 2, 8, 8));
                g.setColor(new Color(0, 0, 0, 85));
                g.draw(new RoundRectangle2D.Float(px - 1, py - 1, sz + 2, sz + 2, 8, 8));
                drawEyes(g, s.x * CELL, s.y * CELL + HUD_H);
            }
        }
    }

    private void drawEyes(Graphics2D g, int ox, int oy) {
        int ex1, ey1, ex2, ey2;
        if (dir.x == 1) {
            ex1 = ox + CELL - 6; ey1 = oy + 4;
            ex2 = ox + CELL - 6; ey2 = oy + CELL - 8;
        } else if (dir.x == -1) {
            ex1 = ox + 3; ey1 = oy + 4;
            ex2 = ox + 3; ey2 = oy + CELL - 8;
        } else if (dir.y == -1) {
            ex1 = ox + 4; ey1 = oy + 3;
            ex2 = ox + CELL - 8; ey2 = oy + 3;
        } else {
            ex1 = ox + 4; ey1 = oy + CELL - 6;
            ex2 = ox + CELL - 8; ey2 = oy + CELL - 6;
        }
        g.setColor(new Color(20, 24, 30));
        g.fillOval(ex1 - 1, ey1 - 1, 6, 6);
        g.fillOval(ex2 - 1, ey2 - 1, 6, 6);
        g.setColor(EYE_W);
        g.fillOval(ex1, ey1, 4, 4);
        g.fillOval(ex2, ey2, 4, 4);
        g.setColor(new Color(15, 18, 24));
        g.fillOval(ex1 + 1, ey1 + 1, 2, 2);
        g.fillOval(ex2 + 1, ey2 + 1, 2, 2);
    }

    private void drawHUD(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, theme.hudAccent.darker(), WIDTH, HUD_H, theme.bg0.darker()));
        g.fillRect(0, 0, WIDTH, HUD_H);
        g.setColor(new Color(255, 255, 255, 35));
        g.drawLine(0, 1, WIDTH, 1);
        g.setColor(theme.hudAccent);
        g.drawLine(0, HUD_H - 1, WIDTH, HUD_H - 1);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(theme.hud);
        String abbr = playerName.length() > 10 ? playerName.substring(0, 10) + "…" : playerName;
        g.drawString(abbr, 10, 17);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("SCORE " + score + "   HI " + hiScore + "   LV " + displayLevel, 10, 33);
        String wall = wrapEdges ? "WRAP" : "WALL";
        String obs = obstacleMode == ObstacleMode.NONE ? "OBS OFF" : "OBS " + obstacleMode.name();
        String diff = difficulty.name().charAt(0) + difficulty.name().substring(1).toLowerCase();
        String right = diff + "  " + wall + "  " + obs;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(right, WIDTH - fm.stringWidth(right) - 10, 25);
        g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
        g.setColor(new Color(theme.hud.getRed(), theme.hud.getGreen(), theme.hud.getBlue(), 200));
        String thn = cap(theme.name());
        FontMetrics fm2 = g.getFontMetrics();
        g.drawString(thn, WIDTH - fm2.stringWidth(thn) - 10, 38);
    }

    private void drawOverlay(Graphics2D g) {
        g.setColor(OVER_BG);
        g.fillRect(0, HUD_H, WIDTH, HEIGHT);

        if (gameOver) {
            drawCenteredString(g, "GAME OVER", new Font(Font.SANS_SERIF, Font.BOLD, 32), OVER_RED, WIDTH / 2, HEIGHT / 2 + HUD_H - 12);
            drawCenteredString(g, playerName + "   Score " + score + "   Lv " + displayLevel, new Font(Font.SANS_SERIF, Font.PLAIN, 14), OVER_GRAY, WIDTH / 2, HEIGHT / 2 + HUD_H + 18);
            drawCenteredString(g, "Settings: Game → Settings… (Ctrl+,)", new Font(Font.SANS_SERIF, Font.PLAIN, 12), OVER_GRAY, WIDTH / 2, HEIGHT / 2 + HUD_H + 40);
            drawCenteredString(g, "[ Enter / Space ] Play again", new Font(Font.SANS_SERIF, Font.BOLD, 13), OVER_GRN, WIDTH / 2, HEIGHT / 2 + HUD_H + 64);
        } else {
            drawCenteredString(g, "SNAKE", new Font(Font.SANS_SERIF, Font.BOLD, 42), OVER_GRN, WIDTH / 2, HEIGHT / 2 + HUD_H - 18);
            drawCenteredString(g, "Configure difficulty, walls, obstacles, theme & colors in Settings", new Font(Font.SANS_SERIF, Font.PLAIN, 12), OVER_GRAY, WIDTH / 2, HEIGHT / 2 + HUD_H + 12);
            drawCenteredString(g, "Arrows or WASD to move", new Font(Font.SANS_SERIF, Font.PLAIN, 12), OVER_GRAY, WIDTH / 2, HEIGHT / 2 + HUD_H + 32);
            drawCenteredString(g, "[ Enter / Space ] Start", new Font(Font.SANS_SERIF, Font.BOLD, 14), OVER_GRN, WIDTH / 2, HEIGHT / 2 + HUD_H + 56);
        }
    }

    private void drawCenteredString(Graphics2D g, String s, Font f, Color c, int cx, int cy) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy);
    }

    private void showStartOverlay() {
        running = false;
        gameOver = false;
        repaint();
    }

    private void startGame() {
        initGame();
        running = true;
        if (timer != null) timer.stop();
        timer = new javax.swing.Timer(tickDelay(), this);
        timer.start();
    }

    private void endGame() {
        running = false;
        gameOver = true;
        if (timer != null) timer.stop();
        if (score > 0) persistScore();
        repaint();
    }

    private void persistScore() {
        List<ScoreRow> rows = loadScores();
        rows.add(new ScoreRow(playerName, score, difficulty, wrapEdges, obstacleMode, theme, System.currentTimeMillis()));
        rows.sort(Comparator.comparingInt((ScoreRow r) -> r.score).reversed());
        while (rows.size() > 20) rows.remove(rows.size() - 1);
        try {
            Files.createDirectories(DATA_DIR);
            String body = rows.stream().map(ScoreRow::toLine).collect(Collectors.joining("\n"));
            Files.writeString(SCORES_PATH, body, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private static List<ScoreRow> loadScores() {
        if (!Files.isRegularFile(SCORES_PATH)) return new ArrayList<>();
        try {
            return Files.readAllLines(SCORES_PATH, StandardCharsets.UTF_8).stream()
                    .map(ScoreRow::parse)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void showLeaderboardDialog(JFrame owner) {
        List<ScoreRow> rows = loadScores();
        rows.sort(Comparator.comparingInt((ScoreRow r) -> r.score).reversed());
        String[] cols = {"#", "Player", "Score", "Lv", "Speed", "Walls", "Obs", "Theme", "When"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        int rank = 1;
        for (ScoreRow r : rows) {
            int lv = 1 + r.score / 5;
            String when = r.whenMs > 0 ? WHEN_FMT.format(Instant.ofEpochMilli(r.whenMs)) : "—";
            model.addRow(new Object[]{
                    rank++,
                    r.name,
                    r.score,
                    lv,
                    cap(r.diff.name()),
                    r.wrap ? "Wrap" : "Solid",
                    r.obs.label,
                    cap(r.th.name()),
                    when
            });
        }
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] w = {28, 100, 48, 32, 64, 52, 52, 72, 100};
        for (int i = 0; i < w.length && i < table.getColumnModel().getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(560, Math.min(360, 48 + rows.size() * 22)));

        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.setBorder(new EmptyBorder(8, 8, 8, 8));
        JLabel title = new JLabel("Leaderboard (last 20 runs, sorted by score)");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        wrap.add(title, BorderLayout.NORTH);
        wrap.add(sp, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(owner, wrap, "Leaderboard", JOptionPane.PLAIN_MESSAGE);
    }

    private static final class ScoreRow {
        final String name;
        final int score;
        final Difficulty diff;
        final boolean wrap;
        final ObstacleMode obs;
        final Theme th;
        final long whenMs;

        ScoreRow(String name, int score, Difficulty diff, boolean wrap, ObstacleMode obs, Theme th, long whenMs) {
            this.name = name;
            this.score = score;
            this.diff = diff;
            this.wrap = wrap;
            this.obs = obs;
            this.th = th;
            this.whenMs = whenMs;
        }

        String toLine() {
            return String.join("|",
                    name.replace("|", " "),
                    Integer.toString(score),
                    diff.name(),
                    wrap ? "1" : "0",
                    obs.name(),
                    th.name(),
                    Long.toString(whenMs));
        }

        static ScoreRow parse(String line) {
            String[] a = line.split("\\|");
            if (a.length < 3) return null;
            try {
                String name = a[0];
                int score = Integer.parseInt(a[1]);
                Difficulty diff = Difficulty.valueOf(a[2]);
                if (a.length >= 7) {
                    boolean wrap = "1".equals(a[3]);
                    ObstacleMode obs = ObstacleMode.valueOf(a[4]);
                    Theme th = Theme.valueOf(a[5]);
                    long when = Long.parseLong(a[6]);
                    return new ScoreRow(name, score, diff, wrap, obs, th, when);
                }
                return new ScoreRow(name, score, diff, true, ObstacleMode.NONE, Theme.FOREST, 0L);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private class KeyHandler extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            int k = e.getKeyCode();
            if (!running && (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE)) {
                startGame();
                return;
            }
            switch (k) {
                case KeyEvent.VK_LEFT, KeyEvent.VK_A -> { if (dir.x != 1) nextDir.setLocation(-1, 0); }
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> { if (dir.x != -1) nextDir.setLocation(1, 0); }
                case KeyEvent.VK_UP, KeyEvent.VK_W -> { if (dir.y != 1) nextDir.setLocation(0, -1); }
                case KeyEvent.VK_DOWN, KeyEvent.VK_S -> { if (dir.y != -1) nextDir.setLocation(0, 1); }
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            JFrame frame = new JFrame();
            SnakeGame game = new SnakeGame();
            game.attachMenus(frame);
            frame.add(game);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
