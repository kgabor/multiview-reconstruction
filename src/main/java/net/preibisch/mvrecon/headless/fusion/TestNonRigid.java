package net.preibisch.mvrecon.headless.fusion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import bdv.util.ConstantRandomAccessible;
import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointList;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.TransformWeight;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.CorrespondingIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.DistanceVisualizingRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.InterpolatingNonRigidRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidTools;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonrigidIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.SimpleReferenceIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval.CombineType;
import net.preibisch.mvrecon.process.fusion.transformed.weights.BlendingRealRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.weights.ContentBasedRealRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.weights.InterpolatingNonRigidRasteredRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.weights.NonRigidRasteredRandomAccessible;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class TestNonRigid
{
	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		SpimData2 spimData;

		// load drosophila
		spimData = new XmlIoSpimData2( "" ).load( "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml" );

		Pair< List< ViewId >, BoundingBox > fused = testInterpolation( spimData, "My Bounding Box" );
		// for bounding box1111 test 128,128,128 vs 256,256,256 (no blocks), there are differences at the edges

		compareToFusion( spimData, fused.getA(), fused.getB() );
	}

	public static void compareToFusion(
			final SpimData2 spimData,
			final List< ViewId > fused,
			final BoundingBox boundingBox )
	{
		// downsampling
		double downsampling = Double.NaN;

		//
		// display virtually fused
		//
		final RandomAccessibleInterval< FloatType > virtual = FusionTools.fuseVirtual( spimData, fused, boundingBox, downsampling );
		DisplayImage.getImagePlusInstance( virtual, true, "Fused Affine", 0, 255 ).show();
	}

	public static Pair< List< ViewId >, BoundingBox > testInterpolation(
			final SpimData2 spimData,
			final String bbTitle )
	{
		BoundingBox boundingBox = null;

		for ( final BoundingBox bb : spimData.getBoundingBoxes().getBoundingBoxes() )
			if ( bb.getTitle().equals( bbTitle ) )
				boundingBox = bb;

		if ( boundingBox == null )
		{
			System.out.println( "Bounding box '" + bbTitle + "' not found." );
			return null;
		}

		IOFunctions.println( BoundingBox.getBoundingBoxDescription( boundingBox ) );

		// select views to process
		final List< ViewId > viewsToFuse = new ArrayList< ViewId >(); // fuse
		final List< ViewId > viewsToUse = new ArrayList< ViewId >(); // used to compute the non-rigid transform

		viewsToUse.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		viewsToFuse.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );
		//viewsToFuse.add( new ViewId( 0, 0 ) );
		//viewsToFuse.add( new ViewId( 0, 1 ) );
		//viewsToFuse.add( new ViewId( 0, 2 ) );
		//viewsToFuse.add( new ViewId( 0, 3 ) );

		// filter not present ViewIds
		List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewsToUse );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		removed = SpimData2.filterMissingViews( spimData, viewsToFuse );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		// downsampling
		double downsampling = Double.NaN;

		//
		// display virtually fused
		//
		final ArrayList< String > labels = new ArrayList<>();

		labels.add( "beads13" );
		labels.add( "nuclei" );

		final int interpolation = 1;
		final long[] controlPointDistance = new long[] { 10, 10, 10 };
		final double alpha = 1.0;

		final boolean useBlending = true;
		final boolean useContentBased = false;
		final boolean displayDistances = false;

		final ExecutorService service = DeconViews.createExecutorService();

		final RandomAccessibleInterval< FloatType > virtual =
				fuseVirtual( spimData, viewsToFuse, viewsToUse, labels, useBlending, useContentBased, displayDistances, controlPointDistance, alpha, interpolation, boundingBox, downsampling, service );

		DisplayImage.getImagePlusInstance( virtual, true, "Fused Non-rigid", 0, 255 ).show();

		return new ValuePair<>( viewsToFuse, boundingBox );
	}

	public static RandomAccessibleInterval< FloatType > fuseVirtual(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final ArrayList< String > labels,
			final boolean useBlending,
			final boolean useContentBased,
			final boolean displayDistances,
			final long[] controlPointDistance,
			final double alpha,
			final int interpolation,
			final Interval boundingBox,
			final double downsampling,
			final ExecutorService service )
	{
		final Interval bb;

		if ( !Double.isNaN( downsampling ) )
			bb = TransformVirtual.scaleBoundingBox( boundingBox, 1.0 / downsampling );
		else
			bb = boundingBox;

		final long[] dim = new long[ bb.numDimensions() ];
		bb.dimensions( dim );

		final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();
		final ArrayList< RandomAccessibleInterval< FloatType > > weights = new ArrayList<>();

		// create final registrations for all views and a list of corresponding interest points
		final HashMap< ViewId, AffineTransform3D > registrations = new HashMap<>();

		for ( final ViewId viewId : viewsToUse )
		{
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();

			final AffineTransform3D model = vr.getModel().copy();

			if ( !Double.isNaN( downsampling ) )
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );

			registrations.put( viewId, model );
		}

		// new loop for interestpoints that need the registrations
		final HashMap< ViewId, ArrayList< CorrespondingIP > > annotatedIps = new HashMap<>();

		for ( final ViewId viewId : viewsToUse )
		{
			final ArrayList< CorrespondingIP > aips = new ArrayList<>();

			for ( final String label : labels )
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading corresponding interest points for " + Group.pvid( viewId ) + ", label '" + label + "'" );
				
				if ( spimData.getViewInterestPoints().getViewInterestPointLists( viewId ).contains( label ) )
				{
					final InterestPointList ipList = spimData.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( label );
					final Map< ViewId, ViewInterestPointLists > interestPointLists = spimData.getViewInterestPoints().getViewInterestPoints();

					final List< CorrespondingInterestPoints > cipList = ipList.getCorrespondingInterestPointsCopy();
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": There are " + cipList.size() + " corresponding interest points in total (to all views)." );

					final ArrayList< CorrespondingIP > aipsTmp = NonRigidTools.assembleAllCorrespondingPoints( viewId, ipList, cipList, viewsToUse, interestPointLists );

					if ( aipsTmp == null )
					{
						IOFunctions.println( new Date( System.currentTimeMillis() ) + ": FAILED to assemble pairs of corresponding interest points." );
						return null;
					}

					aips.addAll( aipsTmp );
				}
				else
				{
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Label '" + label + "' does not exist. Stopping." );
					return null;
				}
			}

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loaded " + aips.size() + " pairs of corresponding interest points." );

			final double dist = NonRigidTools.transformAnnotatedIPs( aips, registrations );

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Average distance = " + dist );

			annotatedIps.put( viewId, aips );
		}

		// compute an average location of each unique interest point that is defined by many (2...n) corresponding interest points
		// this location in world coordinates defines where each individual point should be "warped" to
		final HashMap< ViewId, ArrayList< SimpleReferenceIP > > uniquePoints = NonRigidTools.computeReferencePoints( annotatedIps );

		// compute all grids, if it does not contain a grid we use the old affine model
		final HashMap< ViewId, ModelGrid > nonrigidGrids = NonRigidTools.computeGrids( viewsToFuse, uniquePoints, controlPointDistance, alpha, boundingBox, service );

		// create virtual images
		for ( final ViewId viewId : viewsToFuse )
		{
			final ModelGrid grid = nonrigidGrids.get( viewId );
			final AffineTransform3D model = registrations.get( viewId );

			// this modifies the model so it maps from a smaller image to the global coordinate space,
			// which applies for the image itself as well as the weights since they also use the smaller
			// input image as reference
			// TODO: RandomAccessibleInterval inputImg = DownsampleTools.openDownsampled( spimData.getSequenceDescription().getImgLoader(), viewId, registrations.get( viewId ) );

			RandomAccessibleInterval inputImg = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

			if ( !displayDistances )
			{
				if ( grid == null )
					images.add( TransformView.transformView( inputImg, model, bb, 0, interpolation ) );
				else
					images.add( transformViewNonRigidInterpolated( inputImg, grid, bb, 0, interpolation ) );
			}

			//
			// Display distances
			//
			if ( displayDistances )
			{
				if ( grid == null )
					images.add( Views.interval( new ConstantRandomAccessible< FloatType >( new FloatType( 0 ), 3 ), new FinalInterval( bb ) ) );
				else
					images.add( visualizeDistancesViewNonRigidInterpolated( inputImg, grid, model, bb, 0, interpolation ) );
			}

			//
			// weights
			//
			if ( useBlending || useContentBased )
			{
				RandomAccessibleInterval< FloatType > transformedBlending = null, transformedContentBased = null;

				// instantiate blending if necessary
				if ( useBlending )
				{
					final float[] blending = Util.getArrayFromValue( FusionTools.defaultBlendingRange, 3 );
					final float[] border = Util.getArrayFromValue( FusionTools.defaultBlendingBorder, 3 );

					// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
					FusionTools.adjustBlending( spimData.getSequenceDescription().getViewDescription( viewId ), blending, border, model );

					if ( grid == null )
						transformedBlending = TransformWeight.transformBlending( inputImg, border, blending, model, bb );
					else
						transformedBlending = transformWeightNonRigidInterpolated( new BlendingRealRandomAccessible( new FinalInterval( inputImg ), border, blending ), grid, bb );
				}

				// instantiate content based if necessary
				if ( useContentBased )
				{
					final double[] sigma1 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma1, 3 );
					final double[] sigma2 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma2, 3 );

					// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
					FusionTools.adjustContentBased( spimData.getSequenceDescription().getViewDescription( viewId ), sigma1, sigma2, model );


					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Estimating Entropy for " + Group.pvid( viewId ) );

					if ( grid == null )
						transformedContentBased = TransformWeight.transformContentBased( inputImg, new CellImgFactory< ComplexFloatType >(), sigma1, sigma2, model, bb );
					else
						transformedContentBased = 
							transformWeightNonRigidInterpolated(
									new ContentBasedRealRandomAccessible(
											inputImg,
											new CellImgFactory< ComplexFloatType >( new ComplexFloatType() ),
											sigma1,
											sigma2 ),
									grid,
									bb );
				}

				if ( useContentBased && useBlending )
				{
					weights.add( new CombineWeightsRandomAccessibleInterval(
									new FinalInterval( transformedBlending ),
									transformedBlending,
									transformedContentBased,
									CombineType.MUL ) );
				}
				else if ( useBlending )
				{
					weights.add( transformedBlending );
				}
				else if ( useContentBased )
				{
					weights.add( transformedContentBased );
				}
			}
			else
			{
				final RandomAccessibleInterval< FloatType > imageArea =
						Views.interval( new ConstantRandomAccessible< FloatType >( new FloatType( 1 ), 3 ), new FinalInterval( inputImg ) );

				if ( grid == null )
					weights.add( TransformView.transformView( imageArea, model, bb, 0, 0 ) );
				else
					weights.add( transformViewNonRigidInterpolated( imageArea, grid, bb, 0, 0 ) );
			}
		}

		return new FusedRandomAccessibleInterval( new FinalInterval( dim ), images, weights );
	}

	public static RandomAccessibleInterval< FloatType > transformWeightNonRigidInterpolated(
			final RealRandomAccessible< FloatType > rra,
			final ModelGrid grid,
			final Interval boundingBox )
	{
		final long[] offset = new long[ rra.numDimensions() ];
		final long[] size = new long[ rra.numDimensions() ];

		for ( int d = 0; d < offset.length; ++d )
		{
			offset[ d ] = boundingBox.min( d );
			size[ d ] = boundingBox.dimension( d );
		}

		// the virtual weight construct
		final RandomAccessible< FloatType > virtualBlending =
				new InterpolatingNonRigidRasteredRandomAccessible< FloatType >(
					rra,
					new FloatType(),
					grid,
					offset );

		final RandomAccessibleInterval< FloatType > virtualBlendingInterval = Views.interval( virtualBlending, new FinalInterval( size ) );

		return virtualBlendingInterval;
	}

	public static RandomAccessibleInterval< FloatType > transformWeightNonRigid(
			final RealRandomAccessible< FloatType > rra,
			final Collection< ? extends NonrigidIP > ips,
			final double alpha,
			final Interval boundingBox )
	{
		final long[] offset = new long[ rra.numDimensions() ];
		final long[] size = new long[ rra.numDimensions() ];

		for ( int d = 0; d < offset.length; ++d )
		{
			offset[ d ] = boundingBox.min( d );
			size[ d ] = boundingBox.dimension( d );
		}

		// the virtual weight construct
		final RandomAccessible< FloatType > virtualBlending =
				new NonRigidRasteredRandomAccessible< FloatType >(
					rra,
					new FloatType(),
					ips,
					alpha,
					offset );

		final RandomAccessibleInterval< FloatType > virtualBlendingInterval = Views.interval( virtualBlending, new FinalInterval( size ) );

		return virtualBlendingInterval;
	}

	public static RandomAccessibleInterval< FloatType > visualizeDistancesViewNonRigidInterpolated(
			final Interval input,
			final ModelGrid grid,
			final AffineTransform3D originalTransform,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final long[] size = new long[ input.numDimensions() ];

		for ( int d = 0; d < size.length; ++d )
			size[ d ] = boundingBox.dimension( d );

		final DistanceVisualizingRandomAccessible virtual = new DistanceVisualizingRandomAccessible( input, grid, originalTransform, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( size ) );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformViewNonRigidInterpolated(
			final RandomAccessibleInterval< T > input,
			final ModelGrid grid,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final long[] size = new long[ input.numDimensions() ];

		for ( int d = 0; d < size.length; ++d )
			size[ d ] = boundingBox.dimension( d );

		final InterpolatingNonRigidRandomAccessible< T > virtual = new InterpolatingNonRigidRandomAccessible< T >( input, grid, false, 0.0f, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( size ) );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformViewNonRigid(
			final RandomAccessibleInterval< T > input,
			final Collection< ? extends NonrigidIP > ips,
			final double alpha,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final long[] size = new long[ input.numDimensions() ];

		for ( int d = 0; d < size.length; ++d )
			size[ d ] = boundingBox.dimension( d );

		final NonRigidRandomAccessible< T > virtual = new NonRigidRandomAccessible< T >( input, ips, alpha, false, 0.0f, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( size ) );
	}



}
