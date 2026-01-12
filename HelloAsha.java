import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.io.file.*;
import javax.microedition.media.*;
import java.io.*;
import java.util.*;

public class HelloAsha extends MIDlet implements CommandListener {
    private Display display;
    private List fileList;
    private DemoCanvas demoCanvas;
    private String currentPath = "";
    private Command backCommand, exitCommand;
    
    private byte[] mod;
    private String modTitle = "";
    private Player audioPlayer;
    private boolean isPlaying = false, isRendering = false;
    private int renderProgress = 0;
    private byte[] fullWavData = null;

    // Audio Engine State
    private int tick, row, pattIdx, speed, bpm;
    private int[] chPeriod = new int[4], chIns = new int[4], chPos = new int[4], chVolume = new int[4];
    private int[] insOff = new int[32], insLen = new int[32], insLStart = new int[32], insLLen = new int[32];
    private int[] insFine = new int[32], insVol = new int[32];
    
    // Dynamiczne offsety dla kompatybilności 15/31
    private int headerOffset = 952;
    private int patternOffset = 1084;

    public void startApp() {
        display = Display.getDisplay(this);
        exitCommand = new Command("Exit", Command.EXIT, 1);
        backCommand = new Command("Back", Command.BACK, 2);
        showRoot();
    }

    private void showRoot() {
        currentPath = "";
        fileList = new List("AshaMOD Universal", List.IMPLICIT);
        fileList.addCommand(exitCommand);
        fileList.setCommandListener(this);
        try {
            Enumeration drives = FileSystemRegistry.listRoots();
            while (drives.hasMoreElements()) fileList.append((String) drives.nextElement(), null);
            display.setCurrent(fileList);
        } catch (Exception e) {}
    }

    private void listFolder(String path) {
        try {
            FileConnection fc = (FileConnection) Connector.open("file:///" + path, Connector.READ);
            Enumeration en = fc.list("*", true);
            List nextList = new List(path, List.IMPLICIT);
            nextList.addCommand(backCommand);
            nextList.setCommandListener(this);
            while (en.hasMoreElements()) nextList.append((String) en.nextElement(), null);
            fc.close();
            currentPath = path;
            fileList = nextList;
            display.setCurrent(fileList);
        } catch (Exception e) {}
    }

    private void loadMod(String fileName) {
        try {
            isRendering = true;
            fullWavData = null;
            System.gc();

            FileConnection fc = (FileConnection) Connector.open("file:///" + currentPath + fileName, Connector.READ);
            int size = (int)fc.fileSize();
            mod = new byte[size];
            InputStream is = fc.openInputStream();
            int r = 0; while(r < size) r += is.read(mod, r, size-r);
            is.close(); fc.close();

            // Tytuł
            StringBuffer sb = new StringBuffer();
            for(int i=0; i<20; i++) if(mod[i] >= 32) sb.append((char)mod[i]);
            modTitle = sb.toString().trim();

            // DETEKCJA: 15 vs 31 instrumentów
            int numIns = 31;
            String sig = "";
            if(mod.length > 1084) sig = new String(mod, 1080, 4);
            
            if (!(sig.equals("M.K.") || sig.equals("M!K!") || sig.equals("FLT4") || sig.equals("4CHN"))) {
                numIns = 15;
                headerOffset = 472; // 470 (length) + 2 (restart)
                patternOffset = 600;
            } else {
                numIns = 31;
                headerOffset = 952;
                patternOffset = 1084;
            }

            // Znajdź najwyższy numer patternu
            int pMax = 0; 
            int seqLen = mod[headerOffset - 2] & 0xFF;
            for(int i=0; i<128; i++) { 
                int p = mod[headerOffset + i] & 0xFF; 
                if(p > pMax && p < 128) pMax = p; 
            }
            
            int currentDataPos = patternOffset + ((pMax + 1) * 1024);
            
            // Zerowanie starych danych
            for(int i=0; i<32; i++) { insLen[i]=0; insVol[i]=0; insFine[i]=0; }

            for(int i=1; i<=numIns; i++) {
                int b = 20 + (i-1)*30;
                insLen[i] = (((mod[b+22]&0xFF)<<8) | (mod[b+23]&0xFF)) * 2;
                int ft = mod[b+24] & 0x0F;
                insFine[i] = (ft > 7) ? ft - 16 : ft;
                insVol[i] = mod[b+25] & 0xFF;
                insLStart[i] = (((mod[b+26]&0xFF)<<8) | (mod[b+27]&0xFF)) * 2;
                insLLen[i] = (((mod[b+28]&0xFF)<<8) | (mod[b+29]&0xFF)) * 2;
                insOff[i] = currentDataPos; 
                currentDataPos += insLen[i];
                if (insOff[i] > mod.length) insOff[i] = mod.length;
            }

            demoCanvas = new DemoCanvas();
            demoCanvas.addCommand(backCommand);
            demoCanvas.setCommandListener(this);
            display.setCurrent(demoCanvas);
            startFullRender();
        } catch (Exception e) { isRendering = false; }
    }

    private void startFullRender() {
        new Thread() {
            public void run() {
                try {
                    int outFreq = 16000; 
                    int maxSec = 240; 
                    byte[] audioBuffer = new byte[outFreq * maxSec];
                    
                    int[] chTargetP = new int[4], chLastEff = new int[4], chLastPrm = new int[4];
                    int[] chSlideRate = new int[4];

                    tick = 0; row = 0; pattIdx = 0; speed = 6; bpm = 125;
                    for(int c=0; c<4; c++) { chPeriod[c]=0; chIns[c]=0; chPos[c]=0; chVolume[c]=0; }

                    int actualSamples = 0;
                    long amigaClock = 3546895L;

                    for (int i = 0; i < audioBuffer.length; i++) {
                        if (!isRendering) return;
                        int samplesPerTick = (outFreq * 5) / (bpm * 2);
                        if (i % 10000 == 0) renderProgress = (i * 100) / audioBuffer.length;

                        if (i % samplesPerTick == 0) {
                            if (tick == 0) {
                                int pNum = mod[headerOffset + pattIdx] & 0xFF;
                                int rOff = patternOffset + (pNum * 1024) + (row * 16);
                                
                                if (rOff + 15 < mod.length) {
                                    for (int c = 0; c < 4; c++) {
                                        int o = rOff + (c * 4);
                                        int in = (mod[o] & 0xF0) | ((mod[o+2] & 0xF0) >> 4);
                                        int p = ((mod[o] & 0x0F) << 8) | (mod[o + 1] & 0xFF);
                                        int eff = mod[o + 2] & 0x0F;
                                        int prm = mod[o + 3] & 0xFF;

                                        if (in > 0 && in < 32) { 
                                            chIns[c] = in; 
                                            chVolume[c] = insVol[in]; 
                                        }
                                        if (eff == 0x3) { 
                                            if (p > 0) chTargetP[c] = p;
                                            if (prm > 0) chSlideRate[c] = prm;
                                        } else if (p > 0) {
                                            chPeriod[c] = p; chPos[c] = 0; 
                                        }
                                        chLastEff[c] = eff; chLastPrm[c] = prm;
                                        if (eff == 0xC) chVolume[c] = (prm > 64) ? 64 : prm;
                                        if (eff == 0xF) { 
                                            if (prm > 0 && prm < 32) speed = prm; 
                                            else if (prm >= 32) bpm = prm; 
                                        }
                                        if (eff == 0xD) { row = 63; }
                                    }
                                }
                                row++; 
                                if (row >= 64) { 
                                    row = 0; pattIdx++; 
                                    if (pattIdx >= (mod[headerOffset - 2] & 0xFF)) { actualSamples = i; break; } 
                                }
                            } else {
                                for (int c = 0; c < 4; c++) {
                                    int eff = chLastEff[c], prm = chLastPrm[c];
                                    if (eff == 0x1) chPeriod[c] -= prm; 
                                    if (eff == 0x2) chPeriod[c] += prm;
                                    if (eff == 0x3) {
                                        if (chPeriod[c] < chTargetP[c]) chPeriod[c] = Math.min(chTargetP[c], chPeriod[c] + chSlideRate[c]);
                                        else chPeriod[c] = Math.max(chTargetP[c], chPeriod[c] - chSlideRate[c]);
                                    }
                                    if (eff == 0xA) {
                                        int v = chVolume[c] + (prm >> 4) - (prm & 0x0F);
                                        chVolume[c] = Math.max(0, Math.min(64, v));
                                    }
                                }
                            }
                            tick++; if (tick >= speed) tick = 0;
                        }

                        int mix = 0;
                        for (int c = 0; c < 4; c++) {
                            int ins = chIns[c];
                            if (chPeriod[c] > 20 && ins > 0) {
                                int curP = chPeriod[c];
                                
                                // Efekt 0x0 Arpeggio
                                if (chLastEff[c] == 0x0 && chLastPrm[c] > 0) {
                                    int stepArp = tick % 3;
                                    int noteAdd = (stepArp == 1) ? (chLastPrm[c] >> 4) : (stepArp == 2 ? (chLastPrm[c] & 0x0F) : 0);
                                    if (noteAdd > 0) for(int n=0; n<noteAdd; n++) curP = (int)(curP * 0.9438); 
                                }

                                long step = (amigaClock << 14) / ((long)curP * outFreq);
                                int p1 = chPos[c] >> 14;
                                
                                if (p1 < insLen[ins]) {
                                    mix += (mod[insOff[ins] + p1] * chVolume[c]) >> 6;
                                    chPos[c] += (int)step;
                                    if (insLLen[ins] > 2 && (chPos[c] >> 14) >= insLStart[ins] + insLLen[ins]) {
                                        chPos[c] -= (insLLen[ins] << 14);
                                    }
                                }
                            }
                        }
                        int sval = (mix >> 2) + 128;
                        audioBuffer[i] = (byte)(sval > 255 ? 255 : (sval < 0 ? 0 : sval));
                        actualSamples = i;
                    }
                    
                    byte[] header = createWavHeader(outFreq, actualSamples);
                    fullWavData = new byte[header.length + actualSamples];
                    System.arraycopy(header, 0, fullWavData, 0, header.length);
                    System.arraycopy(audioBuffer, 0, fullWavData, header.length, actualSamples);
                    audioBuffer = null; isRendering = false;
                } catch (Exception e) { isRendering = false; }
            }
        }.start();
    }

    private byte[] createWavHeader(int rate, int len) {
        byte[] h = new byte[44]; h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
        int f = len + 36; h[4]=(byte)(f&0xff); h[5]=(byte)((f>>8)&0xff); h[6]=(byte)((f>>16)&0xff); h[7]=(byte)((f>>24)&0xff);
        h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E'; h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
        h[16]=16; h[20]=1; h[22]=1;
        h[24]=(byte)(rate&0xff); h[25]=(byte)((rate>>8)&0xff); h[26]=(byte)((rate>>16)&0xff); h[27]=(byte)((rate>>24)&0xff);
        h[28]=(byte)(rate&0xff); h[29]=(byte)((rate>>8)&0xff); h[30]=(byte)((rate>>16)&0xff); h[31]=(byte)((rate>>24)&0xff);
        h[32]=1; h[34]=8; h[36]='d'; h[37]='a'; h[38]='t'; h[39]='a';
        h[40]=(byte)(len&0xff); h[41]=(byte)((len>>8)&0xff); h[42]=(byte)((len>>16)&0xff); h[43]=(byte)((len>>24)&0xff);
        return h;
    }

    class DemoCanvas extends Canvas implements Runnable {
        private int[] sX = new int[40], sY = new int[40], sZ = new int[40];
        public DemoCanvas() { 
            setFullScreenMode(true); 
            Random rnd = new Random();
            for(int i=0; i<40; i++) { sX[i]=(rnd.nextInt(200)-100)<<8; sY[i]=(rnd.nextInt(200)-100)<<8; sZ[i]=rnd.nextInt(255)+1; } 
            new Thread(this).start(); 
        }
        public void run() { while (true) { for(int i=0; i<40; i++) { sZ[i]-=6; if(sZ[i]<=0) sZ[i]=255; } repaint(); try { Thread.sleep(40); } catch (Exception e) {} } }
        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight(); g.setColor(0, 0, 20); g.fillRect(0, 0, w, h);
            for(int i=0; i<40; i++) {
                int z = sZ[i]; if(z<1) z=1; int px = (sX[i]/z)+(w/2), py = (sY[i]/z)+(h/2);
                if(px>=0 && px<w && py>=0 && py<h) { int c=255-z; g.setColor(c,c,c); g.drawRect(px,py,1,1); }
            }
            g.setColor(255, 255, 0); g.drawString(modTitle, (w - g.getFont().stringWidth(modTitle))/2, 30, 0);
            if(isRendering) {
                g.setColor(0, 255, 255); g.drawString("Rendering: " + renderProgress + "%", (w - g.getFont().stringWidth("Rendering: 100%"))/2, h/2, 0);
                g.drawRect(20, h/2 + 20, w-40, 10); g.fillRect(21, h/2 + 21, (renderProgress*(w-42))/100, 8);
            } else {
                g.setColor(0, 255, 0); String txt = isPlaying ? "TAP TO STOP" : "TAP TO PLAY";
                g.drawString(txt, (w - g.getFont().stringWidth(txt))/2, h - 45, 0);
            }
        }
        protected void pointerPressed(int x, int y) {
            if (isRendering) return;
            if (isPlaying) { isPlaying = false; if(audioPlayer!=null) try { audioPlayer.stop(); } catch(Exception e){} }
            else if (fullWavData != null) try { audioPlayer = Manager.createPlayer(new ByteArrayInputStream(fullWavData), "audio/x-wav"); audioPlayer.start(); isPlaying = true; } catch (Exception e) {}
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCommand) { isPlaying = false; isRendering = false; notifyDestroyed(); }
        else if (c == backCommand) { 
            isPlaying = false; isRendering = false; if(audioPlayer != null) try { audioPlayer.close(); } catch(Exception e){}
            showRoot(); 
        } else if (d == fileList && c == List.SELECT_COMMAND) {
            String sel = fileList.getString(fileList.getSelectedIndex());
            if (sel.endsWith("/")) listFolder(currentPath + sel); else loadMod(sel);
        }
    }
    public void pauseApp() {}
    public void destroyApp(boolean u) {}
}
