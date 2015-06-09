package com.realface;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.opencv_core.CvFileStorage;
import org.bytedeco.javacpp.opencv_core.CvMat;
import org.bytedeco.javacpp.opencv_core.CvPoint;
import org.bytedeco.javacpp.opencv_core.CvRect;
import org.bytedeco.javacpp.opencv_core.CvSize;
import org.bytedeco.javacpp.opencv_core.CvTermCriteria;
import org.bytedeco.javacpp.opencv_core.IplImage;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_highgui.*;
import static org.bytedeco.javacpp.opencv_legacy.*;

/**
 * Recognizes faces.
 *
 * @author reed
 */
public class FaceRecognition2
{

	/** the number of training faces */
	private int nTrainFaces = 0;
	/** the training face image array */
	IplImage[] trainingFaceImgArr;
	/** the test face image array */
	IplImage[] testFaceImgArr;
	/** the person number array */
	CvMat personNumTruthMat;
	/** the number of persons */
	int nPersons;
	/** the person names */
	final List personNames = new ArrayList<String>();
	/** the number of eigenvalues */
	int nEigens = 0;
	/** eigenvectors */
	IplImage[] eigenVectArr;
	/** eigenvalues */
	CvMat eigenValMat;
	/** the average image */
	IplImage pAvgTrainImg;
	/** the projected training faces */
	CvMat projectedTrainFaceMat;

	public FaceRecognition2()
	{
	}

	/**
	 * Trains from the data in the given training text index file, and store the trained data into the file 'data2/facedata.xml'.
	 *
	 * @param trainingFileName
	 *            the given training text index file
	 */
	public void learn()
	{
		int i;

		// load training data
		System.out.println("===========================================");
		System.out.println("===========================================");
		System.out.println("Loading the training images...");
		trainingFaceImgArr = loadFaceImgArray();
		nTrainFaces = trainingFaceImgArr.length;
		System.out.println("Got " + nTrainFaces + " training images");
		if (nTrainFaces < 3)
		{
			System.out.println("Need 3 or more training faces\n" + "Input file contains only " + nTrainFaces);
			return;
		}

		// do Principal Component Analysis on the training faces
		doPCA();

		System.out.println("projecting the training images onto the PCA subspace");
		// project the training images onto the PCA subspace
		projectedTrainFaceMat = cvCreateMat(nTrainFaces, // rows
				nEigens, // cols
				CV_32FC1); // type, 32-bit float, 1 channel

		// initialize the training face matrix - for ease of debugging
		for (int i1 = 0; i1 < nTrainFaces; i1++)
		{
			for (int j1 = 0; j1 < nEigens; j1++)
			{
				projectedTrainFaceMat.put(i1, j1, 0.0);
			}
		}

		System.out.println("created projectedTrainFaceMat with " + nTrainFaces + " (nTrainFaces) rows and " + nEigens + " (nEigens) columns");
		if (nTrainFaces < 5)
		{
			System.out.println("projectedTrainFaceMat contents:\n" + oneChannelCvMatToString(projectedTrainFaceMat));
		}

		final FloatPointer floatPointer = new FloatPointer(nEigens);
		for (i = 0; i < nTrainFaces; i++)
		{
			cvEigenDecomposite(trainingFaceImgArr[i], // obj
					nEigens, // nEigObjs
					new PointerPointer(eigenVectArr), // eigInput (Pointer)
					0, // ioFlags
					null, // userData (Pointer)
					pAvgTrainImg, // avg
					floatPointer); // coeffs (FloatPointer)

			if (nTrainFaces < 5)
			{
				System.out.println("floatPointer: " + floatPointerToString(floatPointer));
			}
			for (int j1 = 0; j1 < nEigens; j1++)
			{
				projectedTrainFaceMat.put(i, j1, floatPointer.get(j1));
			}
		}
		if (nTrainFaces < 5)
		{
			System.out.println("projectedTrainFaceMat after cvEigenDecomposite:\n" + projectedTrainFaceMat);
		}

		// store the recognition data as an xml file
		storeTrainingData();

		// Save all the eigenvectors as images, so that they can be checked.
		storeEigenfaceImages();
	}

	/**
	 * Recognizes the face in each of the test images given, and compares the results with the truth.
	 *
	 * @param szFileTest
	 *            the index file of test images
	 */
    public void recognizeFileList(final String szFileTest)
    {
        recognizeFileList(szFileTest, null);
    }

	public void recognizeFileList(final String szFileTest, String testFile)
	{
		System.out.println("===========================================");
		System.out.println("recognizing faces indexed from " + szFileTest);
		int i = 0;
		int nTestFaces = 0; // the number of test images
		CvMat trainPersonNumMat; // the person numbers during training
		float[] projectedTestFace;
		String answer;
		int nCorrect = 0;
		int nWrong = 0;
		double timeFaceRecognizeStart;
		double tallyFaceRecognizeTime;
		float confidence = 0.0f;

		// load test images and ground truth for person number
		testFaceImgArr = loadFaceImgArray(szFileTest);
		nTestFaces = testFaceImgArr.length;

		System.out.println(nTestFaces + " test faces loaded");

		// load the saved training data
		trainPersonNumMat = loadTrainingData();
		if (trainPersonNumMat == null)
		{
			return;
		}

		// project the test images onto the PCA subspace
		projectedTestFace = new float[nEigens];
		timeFaceRecognizeStart = (double) cvGetTickCount(); // Record the timing.

		for (i = 0; i < nTestFaces; i++)
		{
			int iNearest;
			int nearest;
			int truth;

			// project the test image onto the PCA subspace
			cvEigenDecomposite(testFaceImgArr[i], // obj
					nEigens, // nEigObjs
					new PointerPointer(eigenVectArr), // eigInput (Pointer)
					0, // ioFlags
					null, // userData
					pAvgTrainImg, // avg
					projectedTestFace); // coeffs

			// System.out.println("projectedTestFace\n" + floatArrayToString(projectedTestFace));

			final FloatPointer pConfidence = new FloatPointer(confidence);
			iNearest = findNearestNeighbor(projectedTestFace, new FloatPointer(pConfidence));
			confidence = pConfidence.get();
			truth = personNumTruthMat.data_i().get(i);
			nearest = trainPersonNumMat.data_i().get(iNearest);

			if (nearest == truth)
			{
				answer = "Correct";
				nCorrect++;
			}
			else
			{
				answer = "WRONG!";
				nWrong++;
			}
			System.out.println("nearest = " + nearest + ", Truth = " + truth + " (" + answer + "). Confidence = " + confidence);
		}
		tallyFaceRecognizeTime = (double) cvGetTickCount() - timeFaceRecognizeStart;
		if (nCorrect + nWrong > 0)
		{
			System.out.println("TOTAL ACCURACY: " + (nCorrect * 100 / (nCorrect + nWrong)) + "% out of " + (nCorrect + nWrong) + " tests.");
			System.out.println("TOTAL TIME: " + (tallyFaceRecognizeTime / (cvGetTickFrequency() * 1000.0 * (nCorrect + nWrong))) + " ms average.");
		}
	}

	/**
	 * Reads the names & image filenames of people from a text file, and loads all those images listed.
	 *
	 * @param filename
	 *            the training file name
	 * @return the face image array
	 */
	private IplImage[] loadFaceImgArray()
	{
		IplImage[] faceImgArr;
		String imgFilename;
		int iFace = 0;
		int nFaces = UserPhoto.count();
		int i;
		try
		{

			System.out.println("nFaces: " + nFaces);

			// allocate the face-image array and person number matrix
			faceImgArr = new IplImage[nFaces];
			personNumTruthMat = cvCreateMat(1, // rows
					nFaces, // cols
					CV_32SC1); // type, 32-bit unsigned, one channel

			// initialize the person number matrix - for ease of debugging
			for (int j1 = 0; j1 < nFaces; j1++)
			{
				personNumTruthMat.put(0, j1, 0);
			}

			personNames.clear(); // Make sure it starts as empty.
			nPersons = 0;

			// store the face images in an array
			for (UserPhoto photo : UserPhoto.findAll())
			{
				String personName = photo.user.name;
				String sPersonName = personName;
				int personNumber = photo.user.id;
				imgFilename = "/Volumes/dmonti/Development/workspace/realface/realface-app/web-app/images/photos/${photo.id}.jpg";
				System.out.println("Got " + iFace + " " + personNumber + " " + personName + " " + imgFilename);

				// Check if a new person is being loaded.
				if (personNumber > nPersons)
				{
					// Allocate memory for the extra person (or possibly multiple), using this new person's name.
					personNames.add(sPersonName);
					nPersons = personNumber;
					System.out.println("Got new person " + sPersonName + " -> nPersons = " + nPersons + " [" + personNames.size() + "]");
				}

				// Keep the data
				personNumTruthMat.put(0, // i
						iFace, // j
						personNumber); // v

				// load the face image
				faceImgArr[iFace] = cvLoadImage(imgFilename, // filename
						CV_LOAD_IMAGE_GRAYSCALE); // isColor

				if (faceImgArr[iFace] == null)
				{
					throw new RuntimeException("Can't load image from " + imgFilename);
				}
			}
		}
		catch (IOException ex)
		{
			throw new RuntimeException(ex);
		}

		System.out.println("Data loaded: (" + nFaces + " images of " + nPersons + " people).");
		final StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("People: ");
		if (nPersons > 0)
		{
			stringBuilder.append("<").append(personNames.get(0)).append(">");
		}
		for (i = 1; i < nPersons && i < personNames.size(); i++)
		{
			stringBuilder.append(", <").append(personNames.get(i)).append(">");
		}
		System.out.println(stringBuilder.toString());

		return faceImgArr;
	}

	/** Does the Principal Component Analysis, finding the average image and the eigenfaces that represent any image in the given dataset. */
	private void doPCA()
	{
		int i;
		CvTermCriteria calcLimit;
		CvSize faceImgSize = new CvSize();

		// set the number of eigenvalues to use
		nEigens = nTrainFaces - 1;

		System.out.println("allocating images for principal component analysis, using " + nEigens + (nEigens == 1 ? " eigenvalue" : " eigenvalues"));

		// allocate the eigenvector images
		faceImgSize.width(trainingFaceImgArr[0].width());
		faceImgSize.height(trainingFaceImgArr[0].height());
		eigenVectArr = new IplImage[nEigens];
		for (i = 0; i < nEigens; i++)
		{
			eigenVectArr[i] = cvCreateImage(faceImgSize, // size
					IPL_DEPTH_32F, // depth
					1); // channels
		}

		// allocate the eigenvalue array
		eigenValMat = cvCreateMat(1, // rows
				nEigens, // cols
				CV_32FC1); // type, 32-bit float, 1 channel

		// allocate the averaged image
		pAvgTrainImg = cvCreateImage(faceImgSize, // size
				IPL_DEPTH_32F, // depth
				1); // channels

		// set the PCA termination criterion
		calcLimit = cvTermCriteria(CV_TERMCRIT_ITER, // type
				nEigens, // max_iter
				1); // epsilon

		System.out.println("computing average image, eigenvalues and eigenvectors");
		// compute average image, eigenvalues, and eigenvectors
		cvCalcEigenObjects(nTrainFaces, // nObjects
				new PointerPointer(trainingFaceImgArr), // input
				new PointerPointer(eigenVectArr), // output
				CV_EIGOBJ_NO_CALLBACK, // ioFlags
				0, // ioBufSize
				null, // userData
				calcLimit, pAvgTrainImg, // avg
				eigenValMat.data_fl()); // eigVals

		System.out.println("normalizing the eigenvectors");
		cvNormalize(eigenValMat, // src (CvArr)
				eigenValMat, // dst (CvArr)
				1, // a
				0, // b
				CV_L1, // norm_type
				null); // mask
	}

	/** Stores the training data to the file 'data2/facedata.xml'. */
	private void storeTrainingData()
	{
		CvFileStorage fileStorage;
		int i;

		System.out.println("writing data2/facedata.xml");

		// create a file-storage interface
		fileStorage = cvOpenFileStorage("/Volumes/dmonti/Development/workspace/realface/realface-app/target/data2/facedata.xml", // filename
				null, // memstorage
				CV_STORAGE_WRITE, // flags
				null); // encoding

		// Store the person names. Added by Shervin.
		cvWriteInt(fileStorage, // fs
				"nPersons", // name
				nPersons); // value

		for (i = 0; i < nPersons; i++)
		{
			String varname = "personName_" + (i + 1);
			cvWriteString(fileStorage, // fs
					varname, // name
					(String) personNames.get(i), // string
					0); // quote
		}

		// store all the data
		cvWriteInt(fileStorage, // fs
				"nEigens", // name
				nEigens); // value

		cvWriteInt(fileStorage, // fs
				"nTrainFaces", // name
				nTrainFaces); // value

		cvWrite(fileStorage, // fs
				"trainPersonNumMat", // name
				personNumTruthMat, // value
				cvAttrList()); // attributes

		cvWrite(fileStorage, // fs
				"eigenValMat", // name
				eigenValMat, // value
				cvAttrList()); // attributes

		cvWrite(fileStorage, // fs
				"projectedTrainFaceMat", // name
				projectedTrainFaceMat, cvAttrList()); // value

		cvWrite(fileStorage, // fs
				"avgTrainImg", // name
				pAvgTrainImg, // value
				cvAttrList()); // attributes

		for (i = 0; i < nEigens; i++)
		{
			String varname = "eigenVect_" + i;
			cvWrite(fileStorage, // fs
					varname, // name
					eigenVectArr[i], // value
					cvAttrList()); // attributes
		}

		// release the file-storage interface
		cvReleaseFileStorage(fileStorage);
	}

	/**
	 * Opens the training data from the file 'data2/facedata.xml'.
	 *
	 * @param pTrainPersonNumMat
	 * @return the person numbers during training, or null if not successful
	 */
	private CvMat loadTrainingData()
	{
		System.out.println("loading training data");
		CvMat pTrainPersonNumMat = null; // the person numbers during training
		CvFileStorage fileStorage;
		int i;

		// create a file-storage interface
		fileStorage = cvOpenFileStorage("/Volumes/dmonti/Development/workspace/realface/realface-app/target/data2/facedata.xml", // filename
				null, // memstorage
				CV_STORAGE_READ, // flags
				null); // encoding
		if (fileStorage == null)
		{
			System.out.println("Can't open training database file 'data2/facedata.xml'.");
			return null;
		}

		// Load the person names.
		personNames.clear(); // Make sure it starts as empty.
		nPersons = cvReadIntByName(fileStorage, // fs
				null, // map
				"nPersons", // name
				0); // default_value
		if (nPersons == 0)
		{
			System.out.println("No people found in the training database 'data2/facedata.xml'.");
			return null;
		}
		else
		{
			System.out.println(nPersons + " persons read from the training database");
		}

		// Load each person's name.
		for (i = 0; i < nPersons; i++)
		{
			String sPersonName;
			String varname = "personName_" + (i + 1);
			sPersonName = cvReadStringByName(fileStorage, // fs
					null, // map
					varname, "");
			personNames.add(sPersonName);
		}
		System.out.println("person names: " + personNames);

		// Load the data
		nEigens = cvReadIntByName(fileStorage, // fs
				null, // map
				"nEigens", 0); // default_value
		nTrainFaces = cvReadIntByName(fileStorage, null, // map
				"nTrainFaces", 0); // default_value
		Pointer pointer = cvReadByName(fileStorage, // fs
				null, // map
				"trainPersonNumMat", // name
				cvAttrList()); // attributes
		pTrainPersonNumMat = new CvMat(pointer);

		pointer = cvReadByName(fileStorage, // fs
				null, // map
				"eigenValMat", // nmae
				cvAttrList()); // attributes
		eigenValMat = new CvMat(pointer);

		pointer = cvReadByName(fileStorage, // fs
				null, // map
				"projectedTrainFaceMat", // name
				cvAttrList()); // attributes
		projectedTrainFaceMat = new CvMat(pointer);

		pointer = cvReadByName(fileStorage, null, // map
				"avgTrainImg", cvAttrList()); // attributes
		pAvgTrainImg = new IplImage(pointer);

		eigenVectArr = new IplImage[nTrainFaces];
		for (i = 0; i < nEigens; i++)
		{
			String varname = "eigenVect_" + i;
			pointer = cvReadByName(fileStorage, null, // map
					varname, cvAttrList()); // attributes
			eigenVectArr[i] = new IplImage(pointer);
		}

		// release the file-storage interface
		cvReleaseFileStorage(fileStorage);

		System.out.println("Training data loaded (" + nTrainFaces + " training images of " + nPersons + " people)");
		final StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("People: ");
		if (nPersons > 0)
		{
			stringBuilder.append("<").append(personNames.get(0)).append(">");
		}
		for (i = 1; i < nPersons; i++)
		{
			stringBuilder.append(", <").append(personNames.get(i)).append(">");
		}
		System.out.println(stringBuilder.toString());

		return pTrainPersonNumMat;
	}

	/** Saves all the eigenvectors as images, so that they can be checked. */
	private void storeEigenfaceImages()
	{
		// Store the average image to a file
		System.out.println("Saving the image of the average face as 'data/out_averageImage.bmp'");
		cvSaveImage("data/out_averageImage.bmp", pAvgTrainImg);

		// Create a large image made of many eigenface images.
		// Must also convert each eigenface image to a normal 8-bit UCHAR image instead of a 32-bit float image.
		System.out.println("Saving the " + nEigens + " eigenvector images as 'data/out_eigenfaces.bmp'");

		if (nEigens > 0)
		{
			// Put all the eigenfaces next to each other.
			int COLUMNS = 8; // Put upto 8 images on a row.
			int nCols = Math.min(nEigens, COLUMNS);
			int nRows = 1 + (nEigens / COLUMNS); // Put the rest on new rows.
			int w = eigenVectArr[0].width();
			int h = eigenVectArr[0].height();
			CvSize size = cvSize(nCols * w, nRows * h);
			final IplImage bigImg = cvCreateImage(size, IPL_DEPTH_8U, // depth, 8-bit Greyscale UCHAR image
					1); // channels
			for (int i = 0; i < nEigens; i++)
			{
				// Get the eigenface image.
				IplImage byteImg = convertFloatImageToUcharImage(eigenVectArr[i]);
				// Paste it into the correct position.
				int x = w * (i % COLUMNS);
				int y = h * (i / COLUMNS);
				CvRect ROI = cvRect(x, y, w, h);
				cvSetImageROI(bigImg, // image
						ROI); // rect
				cvCopy(byteImg, // src
						bigImg, // dst
						null); // mask
				cvResetImageROI(bigImg);
				cvReleaseImage(byteImg);
			}
			cvSaveImage("data/out_eigenfaces.bmp", // filename
					bigImg); // image
			cvReleaseImage(bigImg);
		}
	}

	/**
	 * Converts the given float image to an unsigned character image.
	 *
	 * @param srcImg
	 *            the given float image
	 * @return the unsigned character image
	 */
	private IplImage convertFloatImageToUcharImage(IplImage srcImg)
	{
		IplImage dstImg;
		if ((srcImg != null) && (srcImg.width() > 0 && srcImg.height() > 0))
		{
			// Spread the 32bit floating point pixels to fit within 8bit pixel range.
			CvPoint minloc = new CvPoint();
			CvPoint maxloc = new CvPoint();
			double[] minVal = new double[1];
			double[] maxVal = new double[1];
			// TODO - cvMinMaxLoc(srcImg, minVal, maxVal, minloc, maxloc, null);
			// Deal with NaN and extreme values, since the DFT seems to give some NaN results.
			if (minVal[0] < -1e30)
			{
				minVal[0] = -1e30;
			}
			if (maxVal[0] > 1e30)
			{
				maxVal[0] = 1e30;
			}
			if (maxVal[0] - minVal[0] == 0.0f)
			{
				maxVal[0] = minVal[0] + 0.001; // remove potential divide by zero errors.
			} // Convert the format
			dstImg = cvCreateImage(cvSize(srcImg.width(), srcImg.height()), 8, 1);
			cvConvertScale(srcImg, dstImg, 255.0 / (maxVal[0] - minVal[0]), -minVal[0] * 255.0 / (maxVal[0] - minVal[0]));
			return dstImg;
		}
		return null;
	}

	/**
	 * Find the most likely person based on a detection. Returns the index, and stores the confidence value into pConfidence.
	 *
	 * @param projectedTestFace
	 *            the projected test face
	 * @param pConfidencePointer
	 *            a pointer containing the confidence value
	 * @param iTestFace
	 *            the test face index
	 * @return the index
	 */
	private int findNearestNeighbor(float[] projectedTestFace, FloatPointer pConfidencePointer)
	{
		double leastDistSq = Double.MAX_VALUE;
		int i = 0;
		int iTrain = 0;
		int iNearest = 0;

		System.out.println("................");
		System.out.println("find nearest neighbor from " + nTrainFaces + " training faces");
		for (iTrain = 0; iTrain < nTrainFaces; iTrain++)
		{
			// System.out.println("considering training face " + (iTrain + 1));
			double distSq = 0;

			for (i = 0; i < nEigens; i++)
			{
				// LOGGER.debug("  projected test face distance from eigenface " + (i + 1) + " is " + projectedTestFace[i]);

				float projectedTrainFaceDistance = (float) projectedTrainFaceMat.get(iTrain, i);
				float d_i = projectedTestFace[i] - projectedTrainFaceDistance;
				distSq += d_i * d_i; // / eigenValMat.data_fl().get(i); // Mahalanobis distance (might give better results than Eucalidean distance)
				// if (iTrain < 5) {
				// System.out.println("    ** projected training face " + (iTrain + 1) + " distance from eigenface " + (i + 1) + " is " + projectedTrainFaceDistance);
				// System.out.println("    distance between them " + d_i);
				// System.out.println("    distance squared " + distSq);
				// }
			}

			if (distSq < leastDistSq)
			{
				leastDistSq = distSq;
				iNearest = iTrain;
				System.out.println("  training face " + (iTrain + 1) + " is the new best match, least squared distance: " + leastDistSq);
			}
		}

		// Return the confidence level based on the Euclidean distance,
		// so that similar images should give a confidence between 0.5 to 1.0,
		// and very different images should give a confidence between 0.0 to 0.5.
		float pConfidence = (float) (1.0f - Math.sqrt(leastDistSq / (float) (nTrainFaces * nEigens)) / 255.0f);
		pConfidencePointer.put(pConfidence);

		System.out.println("training face " + (iNearest + 1) + " is the final best match, confidence " + pConfidence);
		return iNearest;
	}

	/**
	 * Returns a string representation of the given float array.
	 *
	 * @param floatArray
	 *            the given float array
	 * @return a string representation of the given float array
	 */
	private String floatArrayToString(final float[] floatArray)
	{
		final StringBuilder stringBuilder = new StringBuilder();
		boolean isFirst = true;
		stringBuilder.append('[');
		for (int i = 0; i < floatArray.length; i++)
		{
			if (isFirst)
			{
				isFirst = false;
			}
			else
			{
				stringBuilder.append(", ");
			}
			stringBuilder.append(floatArray[i]);
		}
		stringBuilder.append(']');

		return stringBuilder.toString();
	}

	/**
	 * Returns a string representation of the given float pointer.
	 *
	 * @param floatPointer
	 *            the given float pointer
	 * @return a string representation of the given float pointer
	 */
	private String floatPointerToString(final FloatPointer floatPointer)
	{
		final StringBuilder stringBuilder = new StringBuilder();
		boolean isFirst = true;
		stringBuilder.append('[');
		for (int i = 0; i < floatPointer.capacity(); i++)
		{
			if (isFirst)
			{
				isFirst = false;
			}
			else
			{
				stringBuilder.append(", ");
			}
			stringBuilder.append(floatPointer.get(i));
		}
		stringBuilder.append(']');

		return stringBuilder.toString();
	}

	/**
	 * Returns a string representation of the given one-channel CvMat object.
	 *
	 * @param cvMat
	 *            the given CvMat object
	 * @return a string representation of the given CvMat object
	 */
	public String oneChannelCvMatToString(final CvMat cvMat)
	{
		// Preconditions
		if (cvMat.channels() != 1)
		{
			throw new RuntimeException("illegal argument - CvMat must have one channel");
		}

		final int type = cvMat.type(); //TODO (?) maskedType();
		StringBuilder s = new StringBuilder("[ ");
		for (int i = 0; i < cvMat.rows(); i++)
		{
			for (int j = 0; j < cvMat.cols(); j++)
			{
				if (type == CV_32FC1 || type == CV_32SC1)
				{
					s.append(cvMat.get(i, j));
				}
				else
				{
					throw new RuntimeException("illegal argument - CvMat must have one channel and type of float or signed integer");
				}
				if (j < cvMat.cols() - 1)
				{
					s.append(", ");
				}
			}
			if (i < cvMat.rows() - 1)
			{
				s.append("\n  ");
			}
		}
		s.append(" ]");
		return s.toString();
	}
}