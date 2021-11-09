/*
 * TraceViewerView.java
 */

package traceviewer;

import java.awt.Image;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import org.jdesktop.application.Action;
import org.jdesktop.application.ResourceMap;
import org.jdesktop.application.SingleFrameApplication;
import org.jdesktop.application.FrameView;
import org.jdesktop.application.TaskMonitor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.Timer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;

/**
 * The application's main frame.
 */
public class TraceViewerView extends FrameView {

    public TraceViewerView(SingleFrameApplication app) {
        super(app);

        initComponents();

        // status bar initialization - message timeout, idle icon and busy animation, etc
        ResourceMap resourceMap = getResourceMap();
        int messageTimeout = resourceMap.getInteger("StatusBar.messageTimeout");
        messageTimer = new Timer(messageTimeout, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                statusMessageLabel.setText("");
            }
        });
        messageTimer.setRepeats(false);
        int busyAnimationRate = resourceMap.getInteger("StatusBar.busyAnimationRate");
        for (int i = 0; i < busyIcons.length; i++) {
            busyIcons[i] = resourceMap.getIcon("StatusBar.busyIcons[" + i + "]");
        }
        busyIconTimer = new Timer(busyAnimationRate, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                busyIconIndex = (busyIconIndex + 1) % busyIcons.length;
                statusAnimationLabel.setIcon(busyIcons[busyIconIndex]);
            }
        });
        idleIcon = resourceMap.getIcon("StatusBar.idleIcon");
        statusAnimationLabel.setIcon(idleIcon);
        progressBar.setVisible(false);

        // connecting action tasks to status bar via TaskMonitor
        TaskMonitor taskMonitor = new TaskMonitor(getApplication().getContext());
        taskMonitor.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                String propertyName = evt.getPropertyName();
                if ("started".equals(propertyName)) {
                    if (!busyIconTimer.isRunning()) {
                        statusAnimationLabel.setIcon(busyIcons[0]);
                        busyIconIndex = 0;
                        busyIconTimer.start();
                    }
                    progressBar.setVisible(true);
                    progressBar.setIndeterminate(true);
                } else if ("done".equals(propertyName)) {
                    busyIconTimer.stop();
                    statusAnimationLabel.setIcon(idleIcon);
                    progressBar.setVisible(false);
                    progressBar.setValue(0);
                } else if ("message".equals(propertyName)) {
                    String text = (String)(evt.getNewValue());
                    statusMessageLabel.setText((text == null) ? "" : text);
                    messageTimer.restart();
                } else if ("progress".equals(propertyName)) {
                    int value = (Integer)(evt.getNewValue());
                    progressBar.setVisible(true);
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(value);
                }
            }
        });

        myInit();
    }

    private void moveHorzScroll(int pos)
    {
        int curpos = scrlHorz.getValue();
        scrlHorz.setValue(pos);
        if (scrlHorz.getValue() == curpos)  // no change?
            drawTrace();
    }

    private void myInit()
    {
        // throw an icon in, just for fun
        ResourceMap resourceMap = org.jdesktop.application.Application.getInstance(traceviewer.TraceViewerApp.class).getContext().getResourceMap(TraceViewerView.class);
        ImageIcon ii = resourceMap.getImageIcon("frame.icon");
        Image i = ii.getImage();
        getFrame().setIconImage(i);

        strBrowser = "firefox"; // default for Linux?

        if (System.getenv("HOME") == null)
            strBrowser = "\"C:\\Program Files\\Internet Explorer\\iexplore.exe\"";    // default for Windows?

        this.getFrame().addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                SaveWindowPrefs();
            }
        });

        this.getFrame().addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowOpened(WindowEvent e)
            {
                LoadWindowPrefs();
            }
        });

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
          .addKeyEventDispatcher(new KeyEventDispatcher()
        {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e)
            {
                if (e.getID() == KeyEvent.KEY_PRESSED && trace != null)
                {
                    // handle shortcuts for keyboard movement of drawn area
                    if (e.getKeyCode() == KeyEvent.VK_HOME)
                    {
                        selstart = 0;
                        selend = selstart;
                        moveHorzScroll(0);
                    }

                    if (e.getKeyCode() == KeyEvent.VK_END)
                    {
                        selstart = scrlHorz.getMaximum();
                        selend = selstart;
                        moveHorzScroll(scrlHorz.getMaximum() - scrlHorz.getBlockIncrement());
                    }

                    if (e.getKeyCode() == KeyEvent.VK_UP)
                    {
                        if (e.isControlDown())
                        {
                            moveCurrentRowUp();
                        }
                        else if (trace.selrow > -1)
                        {
                            trace.selrow--;
                            drawTrace();
                        }
                    }

                    if (e.getKeyCode() == KeyEvent.VK_DOWN)
                    {
                        if (e.isControlDown())
                        {
                            moveCurrentRowDown();
                        }
                        else if (trace.selrow < trace.getRowCount()-1)
                        {
                            trace.selrow++;
                            drawTrace();
                        }
                    }

                    if (e.getKeyCode() == KeyEvent.VK_LEFT)
                    {
                        if (e.isControlDown())
                            moveHorzScroll(scrlHorz.getValue() - scrlHorz.getBlockIncrement());
                        else if (e.isAltDown())
                        {
                            // move cursor to previous sample node in row
                            long prev_pos = trace.findPrevSampleNodePos(selend);
                            long orig_pos = selend;
                            if (e.isShiftDown())
                            {
                                selend = prev_pos;
                            }
                            else
                            {
                                selstart = prev_pos;
                                selend = selstart;
                            }
                            moveHorzScroll(scrlHorz.getValue() + (int)(prev_pos - orig_pos));
                        }
                        else
                            moveHorzScroll(scrlHorz.getValue() - scrlHorz.getUnitIncrement());
                    }

                    if (e.getKeyCode() == KeyEvent.VK_RIGHT)
                    {
                        if (e.isControlDown())
                            moveHorzScroll(scrlHorz.getValue() + scrlHorz.getBlockIncrement());
                        else if (e.isAltDown())
                        {
                            // move cursor to previous sample node in row
                            long next_pos = trace.findNextSampleNodePos(selend);
                            long orig_pos = selend;
                            if (e.isShiftDown())
                            {
                                selend = next_pos;
                            }
                            else
                            {
                                selstart = next_pos;
                                selend = selstart;
                            }
                            moveHorzScroll(scrlHorz.getValue() + (int)(next_pos - orig_pos));
                        }
                        else
                            moveHorzScroll(scrlHorz.getValue() + scrlHorz.getUnitIncrement());
                    }
                }

                return false;
            }
        });
        
        lblGraphic.setTransferHandler(new FileDropHandler(this));

        ToolTipManager.sharedInstance().setInitialDelay(0);
    }

    public void moveCurrentRowDown()
    {
        if (trace.selrow < trace.lstFIDs.size()-1)
        {
            int val1 = trace.lstFIDs.get(trace.selrow);
            int val2 = trace.lstFIDs.get(trace.selrow+1);
            trace.lstFIDs.set(trace.selrow, val2);
            trace.lstFIDs.set(trace.selrow+1, val1);
        }
        trace.selrow++;
        drawTrace();
    }

    public void moveCurrentRowUp()
    {
        if (trace.selrow > 0)
        {
            int val1 = trace.lstFIDs.get(trace.selrow-1);
            int val2 = trace.lstFIDs.get(trace.selrow);
            trace.lstFIDs.set(trace.selrow-1, val2);
            trace.lstFIDs.set(trace.selrow, val1);
        }
        trace.selrow--;
        drawTrace();
    }


    final class FileDropHandler extends TransferHandler {
        TraceViewerView parent;

        public FileDropHandler(TraceViewerView pParent) {
            parent = pParent;
        }

        @Override
        public boolean canImport(TransferHandler.TransferSupport support) {
            for (DataFlavor flavor : support.getDataFlavors()) {
                if (flavor.isFlavorJavaFileListType()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferHandler.TransferSupport support) {
            if (!this.canImport(support)) {
                return false;
            }

            List<File> files;
            try {
                files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            } catch (Exception ex) {
                // should never happen (or JDK is buggy)
                return false;
            }

            //for (File file : files) {
                // do something...
            //}
            parent.LoadTraceFile(files.get(0).getPath());
            return true;
        }
    }
    
    private void LoadWindowPrefs()
    {
        Preferences prefs = Preferences.userNodeForPackage(traceviewer.TraceViewerApp.class);

        // load window prefs
        int x, y, w, h;

        x = prefs.getInt("WindowX", -1);
        y = prefs.getInt("WindowY", -1);
        w = prefs.getInt("WindowWidth", -1);
        h = prefs.getInt("WindowHeight", -1);
        selstart = prefs.getLong("selstart", 0);
        selend = prefs.getLong("selend", 0);
        zoom = prefs.getInt("zoom", 128);
        if (zoom == 0)
            zoom = 128;
        t_pos = prefs.getLong("t_pos", 0);
        String default_trace = prefs.get("DefaultTraceFile", null);
        if (default_trace == null || default_trace.length() == 0)
        {
            String sHome = System.getenv("HOME");
            char seperator = '/';
            if (sHome == null)
            {
                sHome = System.getenv("HOMEPATH");
                seperator = '\\';
            }

            // show a default trace from my recent efforts (for any interested colleagues to easily study)
            default_trace = sHome + seperator + "Apps" + seperator + "TraceViewer" + seperator + "Logs" + seperator + "ffmpeg_vid_audio_with_seek_11sec_bettime.log";
        }
        File fl = new File(default_trace);
        if (fl.exists())
        {
            jTraceChooser.setSelectedFile(fl);
            LoadTraceFile(jTraceChooser.getSelectedFile().getPath());
        }
        String tmpbrws = prefs.get("Browser", null);
        if (tmpbrws != null)
            strBrowser = tmpbrws;
        if (trace != null)
        {
            trace.selrow = prefs.getInt("selrow", -1);
            drawTrace();
        }
        mnuShowDetails.setSelected(prefs.getBoolean("ShowDetails", false));
    }

    private void SaveWindowPrefs()
    {
        Preferences prefs = Preferences.userNodeForPackage(traceviewer.TraceViewerApp.class);

        prefs.put("WindowX", Integer.toString(this.getFrame().getX()));
        prefs.put("WindowY", Integer.toString(this.getFrame().getY()));
        prefs.put("WindowWidth", Integer.toString(this.getFrame().getWidth()));
        prefs.put("WindowHeight", Integer.toString(this.getFrame().getHeight()));
        prefs.putLong("selstart", selstart);
        prefs.putLong("selend", selend);
        prefs.putInt("zoom", zoom);
        prefs.putLong("t_pos", t_pos);
        if (jTraceChooser.getSelectedFile() != null)
            prefs.put("DefaultTraceFile", jTraceChooser.getSelectedFile().getAbsolutePath());
        prefs.put("Browser", strBrowser);
        if (trace != null)
            prefs.putInt("selrow", trace.selrow);
        prefs.putBoolean("ShowDetails", mnuShowDetails.isSelected());
    }

    @Action
    public void showAboutBox() {
        if (aboutBox == null) {
            JFrame mainFrame = TraceViewerApp.getApplication().getMainFrame();
            aboutBox = new TraceViewerAboutBox(mainFrame);
            aboutBox.setLocationRelativeTo(mainFrame);
        }
        TraceViewerApp.getApplication().show(aboutBox);
    }

    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        mainPanel = new javax.swing.JPanel();
        scrlVert = new javax.swing.JScrollBar();
        scrlHorz = new javax.swing.JScrollBar();
        lblGraphic = new javax.swing.JLabel();
        menuBar = new javax.swing.JMenuBar();
        javax.swing.JMenu fileMenu = new javax.swing.JMenu();
        mnuOpenTrace = new javax.swing.JMenuItem();
        javax.swing.JMenuItem exitMenuItem = new javax.swing.JMenuItem();
        optionsMenu = new javax.swing.JMenu();
        mnuZoomIn = new javax.swing.JMenuItem();
        mnuZoomOut = new javax.swing.JMenuItem();
        mnuResetZoom = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        mnuShowDetails = new javax.swing.JCheckBoxMenuItem();
        javax.swing.JMenu helpMenu = new javax.swing.JMenu();
        mnuContents = new javax.swing.JMenuItem();
        mnuUpdateHistory = new javax.swing.JMenuItem();
        mnuReportBug = new javax.swing.JMenuItem();
        javax.swing.JMenuItem aboutMenuItem = new javax.swing.JMenuItem();
        statusPanel = new javax.swing.JPanel();
        javax.swing.JSeparator statusPanelSeparator = new javax.swing.JSeparator();
        statusMessageLabel = new javax.swing.JLabel();
        statusAnimationLabel = new javax.swing.JLabel();
        progressBar = new javax.swing.JProgressBar();
        jTraceChooser = new javax.swing.JFileChooser();

        mainPanel.setName("mainPanel"); // NOI18N
        mainPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent evt) {
                mainPanelComponentResized(evt);
            }
        });

        scrlVert.setName("scrlVert"); // NOI18N

        scrlHorz.setOrientation(javax.swing.JScrollBar.HORIZONTAL);
        scrlHorz.setName("scrlHorz"); // NOI18N
        scrlHorz.addAdjustmentListener(new java.awt.event.AdjustmentListener() {
            public void adjustmentValueChanged(java.awt.event.AdjustmentEvent evt) {
                scrlHorzAdjustmentValueChanged(evt);
            }
        });

        org.jdesktop.application.ResourceMap resourceMap = org.jdesktop.application.Application.getInstance(traceviewer.TraceViewerApp.class).getContext().getResourceMap(TraceViewerView.class);
        lblGraphic.setBackground(resourceMap.getColor("lblGraphic.background")); // NOI18N
        lblGraphic.setText(resourceMap.getString("lblGraphic.text")); // NOI18N
        lblGraphic.setName("lblGraphic"); // NOI18N
        lblGraphic.setOpaque(true);
        lblGraphic.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                lblGraphicMouseClicked(evt);
            }
            public void mousePressed(java.awt.event.MouseEvent evt) {
                lblGraphicMousePressed(evt);
            }
            public void mouseReleased(java.awt.event.MouseEvent evt) {
                lblGraphicMouseReleased(evt);
            }
        });
        lblGraphic.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent evt) {
                lblGraphicComponentResized(evt);
            }
        });
        lblGraphic.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseDragged(java.awt.event.MouseEvent evt) {
                lblGraphicMouseDragged(evt);
            }
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                lblGraphicMouseMoved(evt);
            }
        });

        javax.swing.GroupLayout mainPanelLayout = new javax.swing.GroupLayout(mainPanel);
        mainPanel.setLayout(mainPanelLayout);
        mainPanelLayout.setHorizontalGroup(
            mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(scrlHorz, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 558, Short.MAX_VALUE)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, mainPanelLayout.createSequentialGroup()
                .addComponent(lblGraphic, javax.swing.GroupLayout.DEFAULT_SIZE, 541, Short.MAX_VALUE)
                .addGap(0, 0, 0)
                .addComponent(scrlVert, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
        mainPanelLayout.setVerticalGroup(
            mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, mainPanelLayout.createSequentialGroup()
                .addGroup(mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblGraphic, javax.swing.GroupLayout.DEFAULT_SIZE, 291, Short.MAX_VALUE)
                    .addComponent(scrlVert, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 291, Short.MAX_VALUE))
                .addGap(0, 0, 0)
                .addComponent(scrlHorz, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        menuBar.setName("menuBar"); // NOI18N

        fileMenu.setMnemonic('F');
        fileMenu.setText(resourceMap.getString("fileMenu.text")); // NOI18N
        fileMenu.setName("fileMenu"); // NOI18N

        mnuOpenTrace.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_MASK));
        mnuOpenTrace.setMnemonic('O');
        mnuOpenTrace.setText(resourceMap.getString("mnuOpenTrace.text")); // NOI18N
        mnuOpenTrace.setName("mnuOpenTrace"); // NOI18N
        mnuOpenTrace.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuOpenTraceActionPerformed(evt);
            }
        });
        fileMenu.add(mnuOpenTrace);

        javax.swing.ActionMap actionMap = org.jdesktop.application.Application.getInstance(traceviewer.TraceViewerApp.class).getContext().getActionMap(TraceViewerView.class, this);
        exitMenuItem.setAction(actionMap.get("actionExit")); // NOI18N
        exitMenuItem.setText(resourceMap.getString("exitMenuItem.text")); // NOI18N
        exitMenuItem.setName("exitMenuItem"); // NOI18N
        fileMenu.add(exitMenuItem);

        menuBar.add(fileMenu);

        optionsMenu.setMnemonic('O');
        optionsMenu.setText(resourceMap.getString("optionsMenu.text")); // NOI18N
        optionsMenu.setName("optionsMenu"); // NOI18N

        mnuZoomIn.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS, 0));
        mnuZoomIn.setMnemonic('I');
        mnuZoomIn.setText(resourceMap.getString("mnuZoomIn.text")); // NOI18N
        mnuZoomIn.setName("mnuZoomIn"); // NOI18N
        mnuZoomIn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuZoomInActionPerformed(evt);
            }
        });
        optionsMenu.add(mnuZoomIn);

        mnuZoomOut.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS, 0));
        mnuZoomOut.setMnemonic('O');
        mnuZoomOut.setText(resourceMap.getString("mnuZoomOut.text")); // NOI18N
        mnuZoomOut.setName("mnuZoomOut"); // NOI18N
        mnuZoomOut.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuZoomOutActionPerformed(evt);
            }
        });
        optionsMenu.add(mnuZoomOut);

        mnuResetZoom.setText(resourceMap.getString("mnuResetZoom.text")); // NOI18N
        mnuResetZoom.setName("mnuResetZoom"); // NOI18N
        mnuResetZoom.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuResetZoomActionPerformed(evt);
            }
        });
        optionsMenu.add(mnuResetZoom);

        jSeparator1.setName("jSeparator1"); // NOI18N
        optionsMenu.add(jSeparator1);

        mnuShowDetails.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_MASK));
        mnuShowDetails.setMnemonic('D');
        mnuShowDetails.setText(resourceMap.getString("mnuShowDetails.text")); // NOI18N
        mnuShowDetails.setName("mnuShowDetails"); // NOI18N
        mnuShowDetails.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuShowDetailsActionPerformed(evt);
            }
        });
        optionsMenu.add(mnuShowDetails);

        menuBar.add(optionsMenu);

        helpMenu.setMnemonic('H');
        helpMenu.setText(resourceMap.getString("helpMenu.text")); // NOI18N
        helpMenu.setName("helpMenu"); // NOI18N

        mnuContents.setMnemonic('C');
        mnuContents.setText(resourceMap.getString("mnuContents.text")); // NOI18N
        mnuContents.setName("mnuContents"); // NOI18N
        mnuContents.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuContentsActionPerformed(evt);
            }
        });
        helpMenu.add(mnuContents);

        mnuUpdateHistory.setText(resourceMap.getString("mnuUpdateHistory.text")); // NOI18N
        mnuUpdateHistory.setName("mnuUpdateHistory"); // NOI18N
        mnuUpdateHistory.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuUpdateHistoryActionPerformed(evt);
            }
        });
        helpMenu.add(mnuUpdateHistory);

        mnuReportBug.setText(resourceMap.getString("mnuReportBug.text")); // NOI18N
        mnuReportBug.setName("mnuReportBug"); // NOI18N
        mnuReportBug.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mnuReportBugActionPerformed(evt);
            }
        });
        helpMenu.add(mnuReportBug);

        aboutMenuItem.setAction(actionMap.get("showAboutBox")); // NOI18N
        aboutMenuItem.setName("aboutMenuItem"); // NOI18N
        helpMenu.add(aboutMenuItem);

        menuBar.add(helpMenu);

        statusPanel.setName("statusPanel"); // NOI18N

        statusPanelSeparator.setName("statusPanelSeparator"); // NOI18N

        statusMessageLabel.setName("statusMessageLabel"); // NOI18N

        statusAnimationLabel.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        statusAnimationLabel.setName("statusAnimationLabel"); // NOI18N

        progressBar.setName("progressBar"); // NOI18N

        javax.swing.GroupLayout statusPanelLayout = new javax.swing.GroupLayout(statusPanel);
        statusPanel.setLayout(statusPanelLayout);
        statusPanelLayout.setHorizontalGroup(
            statusPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(statusPanelSeparator, javax.swing.GroupLayout.DEFAULT_SIZE, 558, Short.MAX_VALUE)
            .addGroup(statusPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(statusMessageLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 388, Short.MAX_VALUE)
                .addComponent(progressBar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(statusAnimationLabel)
                .addContainerGap())
        );
        statusPanelLayout.setVerticalGroup(
            statusPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(statusPanelLayout.createSequentialGroup()
                .addComponent(statusPanelSeparator, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(statusPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(statusMessageLabel)
                    .addComponent(statusAnimationLabel)
                    .addComponent(progressBar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(3, 3, 3))
        );

        jTraceChooser.setName("jTraceChooser"); // NOI18N

        setComponent(mainPanel);
        setMenuBar(menuBar);
        setStatusBar(statusPanel);
    }// </editor-fold>//GEN-END:initComponents

    TraceDetails trace = null;

    private int findLargestTimestamp()
    {
        if (trace == null)
            return 0;

        if (trace.lstSamples.size() == 0)
        {
            JOptionPane.showMessageDialog(this.getFrame(), "No samples found", "Error", JOptionPane.ERROR_MESSAGE);
            return 0;
        }
        int idx = trace.lstSamples.size() - 1;
        return (int)trace.lstSamples.get(idx).time_stamp;
    }

    long t_pos = 0;
    private void setScrollBars()
    {
        scrlHorz.setMinimum(0);
        scrlHorz.setMaximum(findLargestTimestamp());
        scrlHorz.setValue((int)t_pos);

        double zm = 100. / (double)zoom;
        int width = lblGraphic.getWidth() - trace.leftcolwidth;
        scrlHorz.setBlockIncrement( (int)(width / zm));
        scrlHorz.setUnitIncrement(scrlHorz.getBlockIncrement() / 30);
    }

    private void LoadTraceFile(String file)
    {
        File fl = new File(file);
        this.getFrame().setTitle("Trace Viewer - \"" + fl.getName() + "\"");

        trace = new TraceDetails(file);
        trace.figureOutLeftColWidth(lblGraphic);

        disableRefreshFlag = true;
        setScrollBars();
        drawTrace();
        disableRefreshFlag = false;
    }

    private void mnuOpenTraceActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_mnuOpenTraceActionPerformed
    {//GEN-HEADEREND:event_mnuOpenTraceActionPerformed
        if (jTraceChooser.showOpenDialog(this.getFrame()) == JFileChooser.APPROVE_OPTION)
        {
            LoadTraceFile(jTraceChooser.getSelectedFile().getPath());
        }
    }//GEN-LAST:event_mnuOpenTraceActionPerformed

    int zoom = 128;
    
    private void mnuZoomInActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_mnuZoomInActionPerformed
    {//GEN-HEADEREND:event_mnuZoomInActionPerformed
        if (trace == null)
            return;

        // figure out new t_pos to keep everything centred on the selection
        t_pos = (selstart+selend) / 2;

        if (zoom == 2)
            return;
        zoom /= 2;

        // now move it back to centre after the zoom is applied
        t_pos = screenToTimelineCoord(trace.leftcolwidth - trace.traceareawidth / 2);

        if (t_pos < 0)
            t_pos = 0;

        disableRefreshFlag = true;
        setScrollBars();
        disableRefreshFlag = false;
        drawTrace();
    }//GEN-LAST:event_mnuZoomInActionPerformed

    private void mnuZoomOutActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_mnuZoomOutActionPerformed
    {//GEN-HEADEREND:event_mnuZoomOutActionPerformed
        if (trace == null)
            return;

        // figure out new t_pos to keep everything centred
        t_pos = (selstart+selend) / 2;

        zoom *= 2;

        // now move it back to centre after the zoom is applied
        t_pos = screenToTimelineCoord(trace.leftcolwidth - trace.traceareawidth / 2);

        if (t_pos < 0)
            t_pos = 0;

        disableRefreshFlag = true;
        setScrollBars();
        disableRefreshFlag = false;
        drawTrace();
    }//GEN-LAST:event_mnuZoomOutActionPerformed

    boolean disableRefreshFlag = false;
    
    private void scrlHorzAdjustmentValueChanged(java.awt.event.AdjustmentEvent evt)//GEN-FIRST:event_scrlHorzAdjustmentValueChanged
    {//GEN-HEADEREND:event_scrlHorzAdjustmentValueChanged
        if (/*!evt.getValueIsAdjusting() &&*/ trace != null && !disableRefreshFlag)
        {
            t_pos = scrlHorz.getValue();
            drawTrace();
        }
    }//GEN-LAST:event_scrlHorzAdjustmentValueChanged

    long selstart = 0;
    long selend = 0;
    boolean selectionInProgress = false;
    
    private void lblGraphicMousePressed(java.awt.event.MouseEvent evt)//GEN-FIRST:event_lblGraphicMousePressed
    {//GEN-HEADEREND:event_lblGraphicMousePressed
        selstart = screenToTimelineCoord(evt.getX());
        selend = selstart;
        selectionInProgress = true;
        statusMessageLabel.setText("");
        drawTrace();
    }//GEN-LAST:event_lblGraphicMousePressed

    private int screenToTimelineCoord(long val)
    {
        double zm = 100. / (double)zoom;
        val = (long)( (val-trace.leftcolwidth) / zm) + t_pos;
        return (int)val;
    }

    private void lblGraphicMouseReleased(java.awt.event.MouseEvent evt)//GEN-FIRST:event_lblGraphicMouseReleased
    {//GEN-HEADEREND:event_lblGraphicMouseReleased
        selend = screenToTimelineCoord(evt.getX());
        
        selectionInProgress = false;
        drawTrace();
    }//GEN-LAST:event_lblGraphicMouseReleased

    private void lblGraphicMouseDragged(java.awt.event.MouseEvent evt)//GEN-FIRST:event_lblGraphicMouseDragged
    {//GEN-HEADEREND:event_lblGraphicMouseDragged
        if (selectionInProgress)
        {
            selend = screenToTimelineCoord(evt.getX());

            drawTrace();
        }
    }//GEN-LAST:event_lblGraphicMouseDragged

    private void drawTrace()
    {
        trace.Draw(lblGraphic, zoom, t_pos, selstart, selend, mnuShowDetails.isSelected());
        showDuration();
    }

    private void showDuration()
    {
        String prefix = "Duration = ";

        long duration = selend - selstart;

        if (duration == 0)
        {
            prefix = "Position = ";
            duration = selstart;
        }
        
        long[] unit_options =
            //us, ms,    s,        m,         h
            { 1L, 1000L, 1000000L, 60000000L, 3600000000L };
        String[] unit_names = { "us", "ms", "s", "m", "h" };

        long units = 0;
        String unit_name = "";
        long abs_dur = duration;
        if (abs_dur < 0) abs_dur = -abs_dur;
        for (int k = 0; k < unit_options.length; k++)
        {
            units = unit_options[k];
            unit_name = unit_names[k];
            if (k < unit_options.length-1 && abs_dur < unit_options[k+1])
                break;
        }

        String sdur = "";
        if (units == 1)
            sdur = Long.toString(duration);
        else
            sdur = String.format("%.1f", (double)duration / (double)units);
        statusMessageLabel.setText(prefix + sdur + unit_name);
    }

    private void lblGraphicComponentResized(java.awt.event.ComponentEvent evt)//GEN-FIRST:event_lblGraphicComponentResized
    {//GEN-HEADEREND:event_lblGraphicComponentResized
        if (!disableRefreshFlag && trace != null)
        {
            drawTrace();
        }
    }//GEN-LAST:event_lblGraphicComponentResized

    private void mnuShowDetailsActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_mnuShowDetailsActionPerformed
    {//GEN-HEADEREND:event_mnuShowDetailsActionPerformed
        if (!disableRefreshFlag && trace != null)
        {
            drawTrace();
        }
    }//GEN-LAST:event_mnuShowDetailsActionPerformed

    private void lblGraphicMouseMoved(java.awt.event.MouseEvent evt)//GEN-FIRST:event_lblGraphicMouseMoved
    {//GEN-HEADEREND:event_lblGraphicMouseMoved
        // test if mouse has hovered over any samples
        // if so, display some sample information (eg, debug-out string)

        if (trace != null && mnuShowDetails.isSelected())
        {
            Point pt = evt.getPoint();
            String strhover = trace.findHoverText(zoom, t_pos, pt);
            lblGraphic.setToolTipText(strhover);
        }
    }//GEN-LAST:event_lblGraphicMouseMoved

    private void lblGraphicMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_lblGraphicMouseClicked
        if (trace != null)
        {
            Point pt = evt.getPoint();
            trace.handleMouseClick(pt);
            drawTrace();
        }
    }//GEN-LAST:event_lblGraphicMouseClicked

    String strBrowser = null;
    
    public void showUrlInBrowser(String url)
    {
        String[] commands = { strBrowser, url };
        try
        {
            Runtime.getRuntime().exec(commands);
        }
        catch (IOException ex)
        {
            Logger.getLogger(TraceViewerView.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void mnuContentsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mnuContentsActionPerformed
        showUrlInBrowser("http://sydlinux1.australia.shufflemaster.com/w/index.php/Trace_Viewer");
    }//GEN-LAST:event_mnuContentsActionPerformed

    private void mnuUpdateHistoryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mnuUpdateHistoryActionPerformed
        showUrlInBrowser("http://sydlinux1.australia.shufflemaster.com/updater/index.php?ProjHistory=TraceViewer");
    }//GEN-LAST:event_mnuUpdateHistoryActionPerformed

    private void mnuReportBugActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mnuReportBugActionPerformed
        showUrlInBrowser("http://dstar00367lx.australia.shufflemaster.com/bugs/enter_bug.cgi?product=Sydlinux1%20Gitorious/Tools/trace-viewer");
    }//GEN-LAST:event_mnuReportBugActionPerformed

    private void mainPanelComponentResized(java.awt.event.ComponentEvent evt)//GEN-FIRST:event_mainPanelComponentResized
    {//GEN-HEADEREND:event_mainPanelComponentResized
        lblGraphic.setIcon(null);
    }//GEN-LAST:event_mainPanelComponentResized

private void mnuResetZoomActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mnuResetZoomActionPerformed
        if (trace == null)
            return;

        // figure out new t_pos to keep everything centred
        t_pos = (selstart+selend) / 2;

        zoom = 128;

        // now move it back to centre after the zoom is applied
        t_pos = screenToTimelineCoord(trace.leftcolwidth - trace.traceareawidth / 2);

        if (t_pos < 0)
            t_pos = 0;

        disableRefreshFlag = true;
        setScrollBars();
        disableRefreshFlag = false;
        drawTrace();
}//GEN-LAST:event_mnuResetZoomActionPerformed

    @Action
    public void actionExit()
    {
        SaveWindowPrefs();
        System.exit(0);
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JFileChooser jTraceChooser;
    private javax.swing.JLabel lblGraphic;
    private javax.swing.JPanel mainPanel;
    private javax.swing.JMenuBar menuBar;
    private javax.swing.JMenuItem mnuContents;
    private javax.swing.JMenuItem mnuOpenTrace;
    private javax.swing.JMenuItem mnuReportBug;
    private javax.swing.JMenuItem mnuResetZoom;
    private javax.swing.JCheckBoxMenuItem mnuShowDetails;
    private javax.swing.JMenuItem mnuUpdateHistory;
    private javax.swing.JMenuItem mnuZoomIn;
    private javax.swing.JMenuItem mnuZoomOut;
    private javax.swing.JMenu optionsMenu;
    private javax.swing.JProgressBar progressBar;
    private javax.swing.JScrollBar scrlHorz;
    private javax.swing.JScrollBar scrlVert;
    private javax.swing.JLabel statusAnimationLabel;
    private javax.swing.JLabel statusMessageLabel;
    private javax.swing.JPanel statusPanel;
    // End of variables declaration//GEN-END:variables

    private final Timer messageTimer;
    private final Timer busyIconTimer;
    private final Icon idleIcon;
    private final Icon[] busyIcons = new Icon[15];
    private int busyIconIndex = 0;

    private JDialog aboutBox;
}
