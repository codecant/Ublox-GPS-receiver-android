package com.hoho.android.usbserial.examples;

/**
 * Created by michaelarthur on 2/09/15.
 */

import android.location.Location;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;


public class NMEAParser {

    // fucking java interfaces
    interface SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position);
    }

    // utils
    static float Latitude2Decimal(String lat, String NS) {

        float med = Float.parseFloat(lat.substring(2)) / 60.0f;
        med += Float.parseFloat(lat.substring(0, 2));
        if (NS.startsWith("S")) {
            med = -med;
        }
        return med;


    }

    static float Longitude2Decimal(String lon, String WE) {
        float med = Float.parseFloat(lon.substring(3)) / 60.0f;
        med += Float.parseFloat(lon.substring(0, 3));
        if (WE.startsWith("W")) {
            med = -med;
        }
        return med;

    }

    // parsers
    class GPGGA implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.time = Float.parseFloat(tokens[1]);
            position.lat = Latitude2Decimal(tokens[2], tokens[3]);
            position.lon = Longitude2Decimal(tokens[4], tokens[5]);
            position.quality = Integer.parseInt(tokens[6]);
            position.altitude = Float.parseFloat(tokens[9]);
            return true;
        }
    }

    class GPGGL implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.lat = Latitude2Decimal(tokens[1], tokens[2]);
            position.lon = Longitude2Decimal(tokens[3], tokens[4]);
            position.time = Float.parseFloat(tokens[5]);
            return true;
        }
    }

    class GPRMC implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.time = Float.parseFloat(tokens[1]);
            position.lat = Latitude2Decimal(tokens[3], tokens[4]);
            position.lon = Longitude2Decimal(tokens[5], tokens[6]);
            position.velocity = Float.parseFloat(tokens[7]);
            position.dir = Float.parseFloat(tokens[8]);
            return true;
        }
    }

    class GPVTG implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.dir = Float.parseFloat(tokens[3]);
            return true;
        }
    }

    class GPRMZ implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.altitude = Float.parseFloat(tokens[1]);
            return true;
        }
    }

    public class GPSPosition {
        public float time = 0.0f;
        public float lat = 0.0f;
        public float lon = 0.0f;
        public boolean fixed = false;
        public int quality = 0;
        public float dir = 0.0f;
        public float altitude = 0.0f;
        public float velocity = 0.0f;

        public void updatefix() {
            fixed = quality > 0;
        }

        public String toString() {
            return String.format("POSITION: lat: %f, lon: %f, time: %f, Q: %d, dir: %f, alt: %f, vel: %f", lat, lon, time, quality, dir, altitude, velocity);
        }
    }

    GPSPosition position = new GPSPosition();

    private static final Map<String, SentenceParser> sentenceParsers = new HashMap<String, SentenceParser>();

    public NMEAParser() {
        sentenceParsers.put("GPGGA", new GPGGA());
        sentenceParsers.put("GPGGL", new GPGGL());
        sentenceParsers.put("GPRMC", new GPRMC());
        sentenceParsers.put("GPRMZ", new GPRMZ());
        //only really good GPS devices have this sentence but ...
        sentenceParsers.put("GPVTG", new GPVTG());
    }

    public GPSPosition parse(String line) {

        if (line.startsWith("$")) {
            String nmea = line.substring(1);
            String[] tokens = nmea.split(",");
            String type = tokens[0];
            //TODO check crc
            if (sentenceParsers.containsKey(type)) {
                try {
                    for (int c = 0; c < tokens.length; c++) {
                        String token = tokens[c];
                        if(token == "") {
                            tokens[c] = "0";
                        }
                    }
                    sentenceParsers.get(type).parse(tokens, position);
                } catch (Exception e) {
                }

            }
            position.updatefix();
        }

        return position;
    }

    public Location location(String str) {
        Location localLocation;
        GPSPosition GPSPos = parse(str);

        if (!GPSPos.fixed) {
            GPSPos = null;
            return null;
        }

        localLocation = new Location("gps");
        localLocation.setLongitude(GPSPos.lon);
        localLocation.setLatitude(GPSPos.lat);
        localLocation.setAltitude(GPSPos.altitude);
        localLocation.setSpeed(GPSPos.velocity);
        localLocation.setBearing(GPSPos.dir);
        localLocation.setTime(System.currentTimeMillis());
        localLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        localLocation.setAccuracy(GPSPos.quality);

        return localLocation;
    }
}