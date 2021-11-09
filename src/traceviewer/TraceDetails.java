/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package traceviewer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.SystemColor;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import javax.swing.JLabel;

/**
 *
 * @author sgp1000
 */
class TraceDetails
{
    Map<Integer, String> mapFuncIds;
    List<Integer> lstFIDs;
    String logfile;
    List<SampleDetails> lstSamples;

    private int readInteger(FileChannel ch) throws IOException
    {
        ByteBuffer bi = ByteBuffer.allocate(4);
        bi.order(ByteOrder.LITTLE_ENDIAN);

        int nRead;
        while ( (nRead = ch.read(bi)) != -1)
        {
            if (nRead == 0)
                continue;
            return bi.getInt(0);
        }
        return -1;
    }

    private long readLong(FileChannel ch) throws IOException
    {
        ByteBuffer bl = ByteBuffer.allocate(8);
        bl.order(ByteOrder.LITTLE_ENDIAN);

        int nRead;
        while ( (nRead = ch.read(bl)) != -1)
        {
            if (nRead == 0)
                continue;
            return bl.getLong(0);
        }
        return -1;
    }

    private BigInteger readLongLong(FileChannel ch) throws IOException
    {
        ByteBuffer bll = ByteBuffer.allocate(16);
        bll.order(ByteOrder.LITTLE_ENDIAN);

        int nRead;
        while ( (nRead = ch.read(bll)) != -1)
        {
            if (nRead == 0)
                continue;

            return new BigInteger(bll.array());
        }

        return null;
    }

    private String readString(FileChannel ch, int bytes) throws IOException
    {
        ByteBuffer bs = ByteBuffer.allocate(bytes);
        int nRead;
        while ( (nRead = ch.read(bs)) != -1)
        {
            if (nRead == 0)
                continue;
            bs.position(0);
            bs.limit(bytes);
            return new String(bs.array());
        }
        return null;
    }

    public TraceDetails(String file)
    {
        mapFuncIds = new HashMap<Integer, String>();
        lstSamples = new ArrayList<SampleDetails>();
        lstFIDs = new ArrayList<Integer>();

        logfile = file;

        try
        {
            FileInputStream f = new FileInputStream(new File(file));
            FileChannel ch = f.getChannel();
            ByteBuffer bb = ByteBuffer.allocate(32768);
            int nRead;

            int num_func_ids = readInteger(ch);

            // read the function-id mapping
            for (int k = 0; k < num_func_ids; k++)
            {
                int func_id = readInteger(ch);
                String func_name = readString(ch, 128).trim();
                mapFuncIds.put(func_id, func_name);
                lstFIDs.add(func_id);

                // allocate colour (if not allocated already
                double mix = 0.6;
                double invmix = 1.0 - mix;
                int r = (int)((mix + invmix * Math.random())*255);
                int g = (int)((mix + invmix * Math.random())*255);
                int b = (int)((mix + invmix * Math.random())*255);
                Color clr = new Color(r, g, b);
                lstColors.add(clr);
            }

            int num_samples = readInteger(ch);

            // read the samples
            for (int k = 0; k < num_samples; k++)
            {
                SampleDetails s = new SampleDetails();
                s.func_id = readInteger(ch);
                s.time_stamp = readLong(ch);
                s.sample_type = readInteger(ch);
                s.exit_point = readInteger(ch);

                if (s.sample_type == TYPE_DEBUGOUT)
                    s.debug_out = readString(ch, 128).trim();

                lstSamples.add(s);
            }
        } catch (Exception ex)
        {
            Logger.getLogger(TraceViewerView.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    int fontheight = 14;
    int leftcolwidth = 0;
    int traceareawidth = 0;

    final int TYPE_ENTER = 0;
    final int TYPE_EXIT = 1;
    final int TYPE_DEBUGOUT = 2;

    Font fntPlain = null;
    Font fntBold = null;

    int fontwidth;
    int rowheight;

    public int getRowCount()
    {
        return mapFuncIds.size();
    }
    
    public int adj(int func_id)
    {
        return lstFIDs.indexOf(func_id);
    }
    
    public void figureOutLeftColWidth(JLabel lblGraphic)
    {
        int width = lblGraphic.getWidth();
        int height = lblGraphic.getHeight();
        BufferedImage bim = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = (Graphics2D)bim.getGraphics();
        fntPlain = new Font("Monospaced", Font.PLAIN, 14);
        fntBold = new Font("Monospaced", Font.BOLD, 14);
        g2d.setFont(fntBold);
        FontMetrics fm = g2d.getFontMetrics();
        fontwidth = fm.stringWidth("x");

        rowheight = fontheight + 1;

        int max_name_len = 0;
        for (Integer func_id : mapFuncIds.keySet())
        {
            String func_name = mapFuncIds.get(func_id);
            g2d.drawString(func_name, 0, (2 + adj(func_id)) * rowheight);

            int len = func_name.length();
            if (len > max_name_len)
                max_name_len = len;
        }

        leftcolwidth = (max_name_len + 1) * fontwidth;
        traceareawidth = width - leftcolwidth;
    }

    Color ltgray = new Color(220, 220, 220);
    List<Color> lstColors = new ArrayList<Color>();
    List<SampleDetails> lstVisibleSamples = new ArrayList<SampleDetails>();
    int detboxsize = 6;

    public void Draw(JLabel lblGraphic, int zoom, long t_pos, long selstart, long selend, boolean showDetails)
    {
        int width = lblGraphic.getWidth();
        int height = lblGraphic.getHeight();

        lstVisibleSamples.clear();

        figureOutLeftColWidth(lblGraphic);

        double zm = 100. / (double) zoom;

        BufferedImage bim = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = (Graphics2D)bim.getGraphics();
        g2d.setFont(fntBold);
        g2d.setColor(Color.white);
        g2d.fill(new Rectangle(0, 0, width, height));
        g2d.setColor(Color.black);

        // it's time to read the data from the 'trace' object and draw it on screen...
        for (Integer func_id : mapFuncIds.keySet())
        {
            String func_name = mapFuncIds.get(func_id);

            if (adj(func_id) == selrow)
            {
                g2d.setColor(Color.yellow);
                g2d.fill(new Rectangle(0, (1 + adj(func_id)) * rowheight + 1, leftcolwidth, rowheight));
                g2d.setColor(Color.blue);
            }
            g2d.drawString(func_name, 0, (2 + adj(func_id)) * rowheight - 2);
            if (adj(func_id) == selrow)
                g2d.setColor(Color.black);
        }

        int x = leftcolwidth;
        int y = 1 * fontheight + 2;
        
        g2d.drawLine(x, fontheight + 1, width, fontheight + 1);
        g2d.drawLine(x-1, 0, x-1, height);

        // draw measure units in top row
        long zdur = (long)( (width-leftcolwidth) / zm) + t_pos;

        long[] unit_options =
            { 100, 200, 500,
              1000, 2000, 5000,
              10000, 20000, 50000,
              100000, 200000, 500000,
              1000000, 2000000, 5000000,
              10000000, 20000000, 50000000,
              100000000, 200000000, 500000000};

        long units = 0;
        for (int k = 0; k < unit_options.length; k++)
        {
            units = unit_options[k];
            int tick_gap = (int)(units * zm);
            if (tick_gap >= 80)
                break;
        }

        Rectangle2D cliprect = new Rectangle2D.Float();
        cliprect.setFrame(x, 0, width, height);
        g2d.setClip(cliprect);

        long unit_cnt = 0;
        long tick = 0;
        for (long k = 0; k < zdur; k += units)
        {
            // decide on us, ms or s units
            String s = "";
            if (units < 1000)
                s = Long.toString(tick*units) + "us";
            else if (units < 1000000)
                s = Long.toString(tick*units/1000) + "ms";
            else if (units < 60000000)
                s = Long.toString(tick*units/1000000) + "s";
            else
                s = Long.toString(tick*units/60000000) + "m";
    
            g2d.drawString(s, x + (int)((tick * units - t_pos) * zm), fontheight);
            unit_cnt += units;
            tick++;
        }

        // draw selected row
        if (selrow != -1)
        {
            g2d.setColor(ltgray);
            g2d.fill(new Rectangle(leftcolwidth, y + selrow*rowheight, traceareawidth, rowheight));
            g2d.setColor(Color.black);
        }

        // group common sample-details together to draw timeline
        long[] lstStarts = new long[mapFuncIds.size()];
        int cnt = 0;
        for (SampleDetails s : lstSamples)
        {
            if (s.func_id == -1)
            {
                System.out.println("cnt="+cnt+", time=" + s.time_stamp);
            }

            if (s.func_id >= mapFuncIds.size())
            {
                System.out.println("Invalid sample-point func_id!\ncnt="+cnt+", s.func_id=" + s.func_id);
                continue;
            }
            if (s.sample_type == TYPE_ENTER)
                lstStarts[s.func_id] = s.time_stamp;
            if (s.sample_type == TYPE_EXIT)
            {
                long start = timelineToScreenCoord(zoom, t_pos, (int)lstStarts[s.func_id]);
                long end = timelineToScreenCoord(zoom, t_pos, (int)s.time_stamp);
                int time = (int)(end-start);

                int ds = (int)start;
                int dw = (int)time;
                g2d.setColor(lstColors.get(s.func_id));
                g2d.fill(new Rectangle(ds, y + adj(s.func_id)*rowheight, dw, fontheight));
                g2d.setColor(Color.black);
                g2d.drawRect(ds, y + adj(s.func_id)*rowheight, dw, fontheight);
            }

            cnt++;
        }

        // show details box
        g2d.setFont(fntPlain);
        g2d.setColor(Color.blue);
        for (SampleDetails s : lstSamples)
        {
            int detx = timelineToScreenCoord(zoom, t_pos, (int)s.time_stamp);
            if (showDetails)
            {
                g2d.fill(new Rectangle(detx-detboxsize/2, y + adj(s.func_id)*rowheight+rowheight/2-detboxsize/2, detboxsize+1, detboxsize+1));

                if (s.sample_type == TYPE_EXIT)
                {
                    // show the exit point number
                    g2d.drawString(Integer.toString(s.exit_point), detx - fontwidth, y + adj(s.func_id)*rowheight+fontheight-1);
                }
            }

            if (detx >= leftcolwidth && detx < width)
                lstVisibleSamples.add(s);

            if (detx > width)
                break;
        }

        // draw any debug info
        for (SampleDetails s : lstSamples)
        {
            int detx = timelineToScreenCoord(zoom, t_pos, (int)s.time_stamp);
            if (selstart == selend && selstart == s.time_stamp)
            {
                String str = findHoverText(zoom, t_pos, selstart);
                if (str != null)
                {
                    FontMetrics fm = g2d.getFontMetrics();
                    String[] split = str.split("[\\n]");
                    for (int k = 0; k < split.length; k++)
                    {
                        g2d.setColor(Color.yellow);
                        Rectangle2D rect = fm.getStringBounds(split[k], g2d);
                        g2d.fillRect(detx, y + adj(s.func_id)*rowheight+35+k*rowheight - rowheight + 2, (int)rect.getWidth(), (int)rect.getHeight());
                        g2d.setColor(Color.black);
                        g2d.drawString(split[k], detx, y + adj(s.func_id)*rowheight+35+k*rowheight);
                    }
                }
            }

            if (detx > width)
                break;
        }

        // show selection
        g2d.setXORMode(Color.white);
        g2d.setColor(Color.lightGray);

        if (selstart > selend)
        {
            long tmp = selstart;
            selstart = selend;
            selend = tmp;
        }
        
        int selx = timelineToScreenCoord(zoom, t_pos, selstart);
        int selw = timelineToScreenCoord(zoom, t_pos, selend) - selx;
        System.out.println("selstart=" + Long.toString(selstart) + "selx = " + Integer.toString(selx) + ", selw = " + selw);
        int sely = y;
        int selh = rowheight * lstStarts.length + 1;
        //System.out.println("selx="+selx+", sely="+sely+", selw="+selw+", selh="+selh);
        g2d.fill(new Rectangle(selx, sely, selw, height));

        // draw selstart and selend cursor positions
        g2d.setColor(Color.blue);
        Stroke default_stroke = g2d.getStroke();
        Stroke dotted = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {4,4}, 0);
        int selx2 = timelineToScreenCoord(zoom, t_pos, selend);
        g2d.setStroke(dotted);
        g2d.drawLine(selx, y, selx, height);
        if (selend != selstart)
            g2d.drawLine(selx2, y, selx2, height);
        g2d.setStroke(default_stroke);

        ImageIcon icon = new ImageIcon(bim);
        lblGraphic.setIcon(icon);
    }

    public long findPrevSampleNodePos(long selstart)
    {
        for (int k = lstSamples.size()-1; k >= 0; k--)
        {
            SampleDetails s = lstSamples.get(k);

            if (adj(s.func_id) == selrow && s.time_stamp < selstart)
                return (int) s.time_stamp;
        }

        return selstart;
    }

    public long findNextSampleNodePos(long selstart)
    {
        for (int k = 0; k < lstSamples.size(); k++)
        {
            SampleDetails s = lstSamples.get(k);

            if (adj(s.func_id) == selrow && s.time_stamp > selstart)
                return (int) s.time_stamp;
        }

        return selstart;
    }

    // this version just checks the cursor position for the current row against any sample points
    public String findHoverText(int zoom, long t_pos, long selpos)
    {
        if (selrow == -1)
            return null;

        for (SampleDetails s : lstVisibleSamples)
        {
            if (adj(s.func_id) != selrow)
                continue;

            if (s.time_stamp == selpos)
            {
                switch (s.sample_type)
                {
                    case TYPE_DEBUGOUT:
                        //String str = "<html>"+s.debug_out+"</html>";
                        //str = str.replace("\n", "<br>");
                        //return str;
                        return s.debug_out;

                    case TYPE_EXIT:
                        return "exit_point: " + s.exit_point;

                    case TYPE_ENTER:
                        return "enter_point";

                    default:
                        return null;
                }
            }
        }

        return null;
    }
    
    public String findHoverText(int zoom, long t_pos, Point pt)
    {
        int x = pt.x;
        int y = pt.y;

        int topy = 1 * fontheight + 2;
        
        // test bounding boxes on all visible points...
        for (SampleDetails s : lstVisibleSamples)
        {
            int detx = timelineToScreenCoord(zoom, t_pos, (int)s.time_stamp);
            int x1 = detx - detboxsize/2;
            int y1 = topy + adj(s.func_id)*rowheight+rowheight/2-detboxsize/2;
            int x2 = x1 + detboxsize;
            int y2 = y1 + detboxsize;
            
            if ( (x1 < x && x < x2) && (y1 < y && y < y2))
            {
                switch (s.sample_type)
                {
                    case TYPE_DEBUGOUT:
                        String str = "<html>"+s.debug_out+"</html>";
                        str = str.replace("\n", "<br>");
                        return str;
                        
                    case TYPE_EXIT:
                        return "exit_point: " + s.exit_point;

                    case TYPE_ENTER:
                        return "enter_point";
                        
                    default:
                        return null;
                }
            }
        }

        return null;
    }

    public int selrow = -1;

    public void handleMouseClick(Point pt)
    {
        int row = (pt.y - 3 - fontheight) / rowheight;
        if (row >= 0 && row < mapFuncIds.size())
        {
            selrow = row;
        }
    }

    public int timelineToScreenCoord(int zoom, long x_pos, long val)
    {
        double zm = 100. / (double)zoom;
        val = (long)((val - x_pos) * zm) + leftcolwidth;
        return (int)val;
    }

}
