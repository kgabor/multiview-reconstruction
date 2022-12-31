/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2022 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.mvrecon.process.export;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import fiji.util.gui.GenericDialogPlus;
import ij.ImagePlus;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.exception.ImgLibException;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionExportInterface;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class DisplayImage implements ImgExport, Calibrateable
{
	// TODO: this is ugly, but otherwise the service is shutdown while the ImageJVirtualStack is still displayed and crashes when scrolling through the stack
	final static ExecutorService service = DeconViews.createExecutorService();

	public static int defaultChoice = 1;
	final String[] choiceText = new String[] { "cached (immediate, less memory, slower)", "precomputed (fast, complete copy in memory before display)" };

	boolean virtualDisplay;

	String unit = "px";
	double cal = 1.0;

	public DisplayImage() { this( true ); }
	public DisplayImage( final boolean virtualDisplay ) { this.virtualDisplay = virtualDisplay; }

	public < T extends RealType< T > & NativeType< T > > void exportImage( final RandomAccessibleInterval< T > img )
	{
		exportImage( img, null, Double.NaN, Double.NaN, "Image", null );
	}

	public < T extends RealType< T > & NativeType< T > > void exportImage( final RandomAccessibleInterval< T > img, final String title )
	{
		exportImage( img, null, Double.NaN, Double.NaN, title, null );
	}

	@Override
	public < T extends RealType< T > & NativeType< T > > boolean exportImage(
			final RandomAccessibleInterval< T > img,
			final Interval bb,
			final double downsampling,
			final double anisoF,
			final String title,
			final Group< ? extends ViewId > fusionGroup )
	{
		// do nothing in case the image is null
		if ( img == null )
			return false;

		// determine min and max
		final double[] minmax = FusionTools.minMaxApprox( null );//getFusionMinMax( img, min, max );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Approximate min=" + minmax[ 0 ] + ", max=" + minmax[ 1 ] );

		final ImagePlus imp = getImagePlusInstance( img, virtualDisplay, title, minmax[ 0 ], minmax[ 1 ] );

		setCalibration( imp, bb, downsampling, anisoF, cal, unit );

		imp.updateAndDraw();
		imp.show();

		return true;
	}

	public static void setCalibration( final ImagePlus imp, final Interval bb, final double downsampling, final double anisoF, final double cal, final String unit )
	{
		final double ds = Double.isNaN( downsampling ) ? 1.0 : downsampling;
		final double ai = Double.isNaN( anisoF ) ? 1.0 : anisoF;

		if ( bb != null )
		{
			imp.getCalibration().xOrigin = -(bb.min( 0 ) / ds) * cal;
			imp.getCalibration().yOrigin = -(bb.min( 1 ) / ds) * cal;
			imp.getCalibration().zOrigin = -(bb.min( 2 ) / ds) * cal;
			imp.getCalibration().pixelWidth = imp.getCalibration().pixelHeight = ds * cal;
			imp.getCalibration().pixelDepth = ds * ai * cal;
		}

		imp.getCalibration().setUnit( unit );
	}

	public static < T extends RealType< T > > double[] getFusionMinMax(
			final RandomAccessibleInterval<T> img,
			final double min,
			final double max )
	{
		final double[] minmax;

		if ( Double.isNaN( min ) || Double.isNaN( max ) )
			minmax = FusionTools.minMaxApprox( img );
		else
			minmax = new double[]{ (float)min, (float)max };

		return minmax;
	}

	public static < T extends RealType< T > & NativeType< T > > ImagePlus getImagePlusInstance(
			final RandomAccessibleInterval< T > img,
			final boolean virtualDisplay,
			final String title,
			final double min,
			final double max )
	{
		return getImagePlusInstance( img, virtualDisplay, title, min, max, service );
	}

	public static < T extends RealType< T > & NativeType< T > > ImagePlus getImagePlusInstance(
			final RandomAccessibleInterval< T > img,
			final boolean virtualDisplay,
			final String title,
			final double min,
			final double max,
			final ExecutorService service )
	{
		ImagePlus imp = null;

		if ( img instanceof ImagePlusImg )
			try { imp = ((ImagePlusImg<T, ?>)img).getImagePlus(); } catch (ImgLibException e) {}

		if ( imp == null )
		{
			if ( virtualDisplay )
				imp = ImageJFunctions.wrap( img, title, service );
			else
				imp = ImageJFunctions.wrap( img, title, service ).duplicate();
		}

		final double[] minmax = getFusionMinMax( img, min, max );

		imp.setTitle( title );
		imp.setDimensions( 1, (int)img.dimension( 2 ), 1 );
		imp.setDisplayRange( minmax[ 0 ], minmax[ 1 ] );

		return imp;
	}

	@Override
	public boolean queryParameters( final FusionExportInterface fusion )
	{
		if ( !FusionTools.is2d( fusion.getViews().stream().map( v -> fusion.getSpimData().getSequenceDescription().getViewDescriptions().get( v ) ).collect( Collectors.toList() ) ) )
		{
			final GenericDialogPlus gd = new GenericDialogPlus( "Display fused image as ImageJ stack" );
	
			gd.addChoice( "Display image", choiceText, choiceText[ defaultChoice ] );
	
			gd.showDialog();
			if ( gd.wasCanceled() )
				return false;
	
			if ( ( defaultChoice = gd.getNextChoiceIndex() ) == 0 )
				virtualDisplay = true;
			else
				virtualDisplay = false;
		}

		return true;
	}

	@Override
	public ImgExport newInstance() { return new DisplayImage(); }

	@Override
	public String getDescription() { return "Display using ImageJ"; }

	@Override
	public boolean finish()
	{
		// this spimdata object was not modified
		return false;
	}

	@Override
	public void setCalibration( final double pixelSize, final String unit )
	{
		this.cal = pixelSize;
		this.unit = unit;
	}

	@Override
	public String getUnit() { return unit; }

	@Override
	public double getPixelSize() { return cal; }

	@Override
	public int[] blocksize() { return new int[] { 256, 256, 1 }; }
}
