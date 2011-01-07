// Created by plusminus on 19:24:16 - 12.11.2008
package org.androad.osm.views.tiles.util;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.andnav.osm.tileprovider.CloudmadeException;
import org.andnav.osm.tileprovider.IOpenStreetMapTileProviderCallback;
import org.andnav.osm.tileprovider.IRegisterReceiver;
import org.andnav.osm.tileprovider.OpenStreetMapTile;
import org.andnav.osm.tileprovider.OpenStreetMapTileFilesystemProvider;
import org.andnav.osm.util.GeoPoint;
import org.andnav.osm.views.OpenStreetMapView;
import org.andnav.osm.views.util.IOpenStreetMapRendererInfo;
import org.andnav.osm.views.util.constants.OpenStreetMapViewConstants;

import org.androad.osm.util.ValuePair;
import org.androad.osm.util.Util.PixelSetter;
import org.androad.osm.util.constants.OSMConstants;
import org.androad.osm.views.util.Util;
import org.androad.sys.ors.adt.rs.Route;

import android.os.Handler;
import android.os.Message;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

public class OSMMapTilePreloader implements OSMConstants, OpenStreetMapViewConstants {
	// ===========================================================
	// Constants
	// ===========================================================
	
	// ===========================================================
	// Fields
	// ===========================================================

	// ===========================================================
	// Constructors
	// ===========================================================

	// ===========================================================
	// Getter & Setter
	// ===========================================================

	// ===========================================================
	// Methods from SuperClass/Interfaces
	// ===========================================================

	// ===========================================================
	// Methods
	// ===========================================================

	/**
	 * Loads all MapTiles needed to cover a route at a specific zoomlevel.
	 */
	public void loadAllToCacheAsync(final Route aRoute, final int aZoomLevel, final IOpenStreetMapRendererInfo aRendererInfo, final OnProgressChangeListener pProgressListener, final boolean pSmoothed) throws IllegalArgumentException {
		loadAllToCacheAsync(OSMMapTilePreloader.getNeededMaptiles(aRoute, aZoomLevel, aRendererInfo, pSmoothed), aRendererInfo, pProgressListener);
	}

	/**
	 * Loads a series of MapTiles to the various caches at a specific zoomlevel.
	 */
	public void loadAllToCacheAsync(final OpenStreetMapTile[] pTiles, final IOpenStreetMapRendererInfo aRendererInfo, final OnProgressChangeListener pProgressListener){
        final OpenStreetMapTileFilesystemProvider provider;
		final int overallCount = pTiles.length;

        int sendingCount = 0;
        final int limit = Math.min(overallCount, OpenStreetMapTileFilesystemProvider.TILE_FILESYSTEM_MAXIMUM_QUEUE_SIZE);
		final OpenStreetMapTileProviderCallback cbk = new OpenStreetMapTileProviderCallback(limit, overallCount, pTiles, pProgressListener);
		final RegisterReceiver reg = new RegisterReceiver();

        provider = new OpenStreetMapTileFilesystemProvider(cbk, reg);
        cbk.setProvider(provider);

        for (final OpenStreetMapTile tile : pTiles) {
            provider.loadMapTileAsync(tile);
            sendingCount++;
            if (sendingCount >= OpenStreetMapTileFilesystemProvider.TILE_FILESYSTEM_MAXIMUM_QUEUE_SIZE) {
                break;
            }
        }
	}


	/**
	 * 
	 * @param aRoute
	 * @param aZoomLevel
	 * @param aProviderInfo
	 * @param pSmoothed Smoothed by a Bresenham-Algorithm
	 * @return
	 * @throws IllegalArgumentException
	 */
	public static OpenStreetMapTile[] getNeededMaptiles(final Route aRoute, final int aZoomLevel, final IOpenStreetMapRendererInfo aProviderInfo, final boolean pSmoothed) throws IllegalArgumentException {
		return getNeededMaptiles(aRoute.getPolyLine(), aZoomLevel, aProviderInfo, pSmoothed);
	}

	/**
	 * 
	 * @param aPath
	 * @param aZoomLevel
	 * @param aProviderInfo
	 * @param pSmoothed Smoothed by a Bresenham-Algorithm
	 * @return
	 * @throws IllegalArgumentException
	 */
	public static OpenStreetMapTile[] getNeededMaptiles(final List<GeoPoint> aPath, final int aZoomLevel, final IOpenStreetMapRendererInfo aProviderInfo, final boolean pSmoothed) throws IllegalArgumentException {
		if(aZoomLevel > aProviderInfo.zoomMaxLevel()) {
			throw new IllegalArgumentException("Zoomlevel higher than Renderer supplies!");
		}

		/* We need only unique MapTile-indices, so we use a Set. */
		final Set<ValuePair> needed = new TreeSet<ValuePair>(new Comparator<ValuePair>(){
			@Override
			public int compare(final ValuePair a, final ValuePair b) {
				return a.compareTo(b);
			}
		});

		/* Contains the values of a single line. */
		final Set<ValuePair> rasterLine = new TreeSet<ValuePair>(new Comparator<ValuePair>(){
			@Override
			public int compare(final ValuePair a, final ValuePair b) {
				return a.compareTo(b);
			}
		});

		final PixelSetter rasterLinePixelSetter = new PixelSetter(){
			@Override
			public void setPixel(final int x, final int y) {
				rasterLine.add(new ValuePair(x,y));
			}
		};

		OpenStreetMapTile cur = null;

		GeoPoint previous = null;
		/* Get the mapTile-coords of every point in the polyline and add to the set. */
		for (final GeoPoint gp : aPath) {
			cur = Util.getMapTileFromCoordinates(aProviderInfo, gp, aZoomLevel);
			needed.add(new ValuePair(cur.getX(), cur.getY()));

			if(previous != null){
				final int prevX = cur.getX();
				final int prevY = cur.getY();

				cur = Util.getMapTileFromCoordinates(aProviderInfo, GeoPoint.fromCenterBetween(gp, previous), aZoomLevel);

				final int curX = cur.getX();
				final int curY = cur.getY();

				rasterLine.clear();
				org.androad.osm.util.Util.rasterLine(prevX, prevY, curX, curY, rasterLinePixelSetter);

				/* If wanted smooth that line. */
				if(pSmoothed){
					org.androad.osm.util.Util.smoothLine(rasterLine);
				}

				needed.addAll(rasterLine);
			}

			previous = gp;
		}

		/* Put the unique MapTile-indices into an array. */
		final int countNeeded = needed.size();
		final OpenStreetMapTile[] out = new OpenStreetMapTile[countNeeded];

		int i = 0;
		for (final ValuePair valuePair : needed) {
			out[i++] = new OpenStreetMapTile(aProviderInfo, aZoomLevel, valuePair.getValueA(), valuePair.getValueB());
		}

		return out;
	}


	// ===========================================================
	// Inner and Anonymous Classes
	// ===========================================================

	public abstract class OnProgressChangeListener extends Handler{

		/** Between 0 and 100 (including). */
		public abstract void onProgressChange(final int aProgress, final int aMax);

        @Override
        public void handleMessage(Message msg) {
            this.onProgressChange(msg.arg1, msg.arg2);
        }
	}

    private class RegisterReceiver implements IRegisterReceiver{
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            return null;
        }

        public void unregisterReceiver(BroadcastReceiver receiver) {
        }
    }

    private class OpenStreetMapTileProviderCallback implements IOpenStreetMapTileProviderCallback{
        int sendingCount = 0;
        int successCount = 0;
        final int overallCount;
        final OpenStreetMapTile[] pTiles;
        OpenStreetMapTileFilesystemProvider provider;
        final OnProgressChangeListener pProgressListener;

        public OpenStreetMapTileProviderCallback(final int sendingCount, final int overallCount,
                                                 final OpenStreetMapTile[] pTiles,
                                                 final OnProgressChangeListener pProgressListener) {
            this.sendingCount = sendingCount;
            this.overallCount = overallCount;
            this.pTiles = pTiles;
            this.pProgressListener = pProgressListener;
        }

        public void setProvider(final OpenStreetMapTileFilesystemProvider provider) {
            this.provider = provider;
        }

        public String getCloudmadeKey() throws CloudmadeException {
            return null;
        }

        public void mapTileRequestCompleted(OpenStreetMapTile aTile, String aTilePath) {
            if (sendingCount < overallCount) {
                provider.loadMapTileAsync(pTiles[sendingCount]);
                sendingCount++;
            }

            successCount++;
            Message msg;
            msg = pProgressListener.obtainMessage(0, successCount, overallCount);
            if(successCount >= overallCount) {
                msg = pProgressListener.obtainMessage(0, overallCount, overallCount);
            }

            msg.sendToTarget();
            if(DEBUGMODE) {
                Log.i(DEBUGTAG, "MapTile download success.");
            }
        }

        public void mapTileRequestCompleted(OpenStreetMapTile aTile, InputStream aTileInputStream) {
            mapTileRequestCompleted(aTile, "");
        }

        public void mapTileRequestCompleted(OpenStreetMapTile aTile) {
            mapTileRequestCompleted(aTile, "");
        }

        public boolean useDataConnection(){return true;}
    }

}
