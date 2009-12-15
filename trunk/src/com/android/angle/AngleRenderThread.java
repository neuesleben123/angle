package com.android.angle;

import java.util.concurrent.Semaphore;

import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.opengles.GL11;

import android.os.Handler;
import android.util.Log;

/**
 * Main render thread based on API demos render thread
 * 
 * @author Ivan Pajuelo
 * 
 */
public class AngleRenderThread extends Thread
{
	private static final int[] configSpec = { EGL11.EGL_DEPTH_SIZE, 0, EGL11.EGL_NONE };
	private static final Semaphore sEglSemaphore = new Semaphore(1);
	private boolean mDone = false;
	private boolean mPaused = true;
	private boolean mHasFocus = false;
	private boolean mHasSurface = false;
	private boolean mContextLost = true;
	private int mWidth = 0;
	private int mHeight = 0;
	protected AngleAbstractGameEngine mGameEngine = null;
	private EGLHelper mEglHelper = null;
	public static GL11 gl = null;
	private boolean needStartEgl = false;
	private boolean needResize = false;
	private AngleMainEngine mRenderEngine;
	public Handler mHandler = null;
	private AngleAbstractGameEngine mNewGameEngine = null;

	AngleRenderThread(AngleMainEngine renderEngine)
	{
		super();
		mRenderEngine = renderEngine;
		setName("AngleRenderThread");
	}

	@Override
	public void run()
	{
		try
		{
			try
			{
				sEglSemaphore.acquire();
			}
			catch (InterruptedException e)
			{
				Log.e("AngleRenderThread", "sEglSemaphore.acquire() exception: " + e.getMessage());
				return;
			}
			guardedRun();
		}
		catch (InterruptedException e)
		{
			Log.e("AngleRenderThread", "guarded() exception: " + e.getMessage());
		}
		finally
		{
			mRenderEngine.onDestroy(gl);
			sEglSemaphore.release();
		}
	}

	private void guardedRun() throws InterruptedException
	{
		mEglHelper = new EGLHelper();
		mEglHelper.start(configSpec);

		long lCTM = 0;

		mDone = false;

		while (!mDone)
		{
			synchronized (this)
			{
				if (mPaused)
				{
					mEglHelper.finish();
					needStartEgl = true;
					Log.d("AngleRenderThread", "Paused!");
				}
				if (needToWait())
				{
					Log.d("AngleRenderThread", "Waiting...");
					while (needToWait())
					{
						wait();
					}
					Log.d("AngleRenderThread", "End wait!");
					lCTM = 0;
				}
				if (mDone)
				{
					Log.d("AngleRenderThread", "run break");
					break;
				}

				// Captures variables values synchronized
				if (AngleSurfaceView.mSizeChanged)
				{
					AngleSurfaceView.mSizeChanged = false;
					gl = null;
				}
			}
			if (mNewGameEngine != null)
				changeGameEngine(gl);
			if (needStartEgl)
			{
				needStartEgl = false;
				Log.d("AngleRenderThread", "Need Start EGL");
				mEglHelper.start(configSpec);
				gl = null;
			}
			if (gl == null)
			{
				AngleMainEngine.mTexturesLost = true;
				AngleMainEngine.mBuffersLost = true;
				Log.d("AngleRenderThread", "needCreateSurface");
				gl = (GL11) mEglHelper.createSurface(AngleSurfaceView.mSurfaceHolder);
				AngleTextureEngine.genTextures(gl);
				needResize = true;
				lCTM=0;
			}
			if (AngleMainEngine.mTexturesLost)
			{
				Log.d("AngleRenderThread", "needLoadTextures");
				mRenderEngine.beforeLoadTextures(gl);
				AngleTextureEngine.loadTextures();
				mRenderEngine.afterLoadTextures(gl);
			}
			if (AngleMainEngine.mBuffersLost)
			{
				AngleMainEngine.mBuffersLost = false;
				Log.d("AngleRenderThread", "needCreateBuffers");
				mRenderEngine.createBuffers(gl);
			}
			if (needResize)
			{
				needResize = false;
				Log.d("AngleRenderThread", "needResize");
				AngleMainEngine.sizeChanged(gl, mWidth, mHeight);
			}
			if ((gl!=null) && (!AngleMainEngine.mBuffersLost) && (!AngleMainEngine.mTexturesLost))
			{
				if (mHasSurface && (AngleMainEngine.mWidth > 0) && (AngleMainEngine.mHeight > 0))
				{
					AngleMainEngine.secondsElapsed = 0.0f;
					long CTM = System.currentTimeMillis();
					if (lCTM > 0)
						AngleMainEngine.secondsElapsed = (CTM - lCTM) / 1000.f;
					lCTM = CTM;
	
					if (AngleMainEngine.mContextLost)
					{
						mHandler.sendEmptyMessage(AngleMainEngine.MSG_CONTEXT_LOST);
						mPaused = true;
					}
	
					if (mGameEngine != null)
						mGameEngine.run();
	
					mRenderEngine.drawFrame(gl);
	
					mEglHelper.swap();
				}
			}
		}

		mEglHelper.finish();
	}

	private void changeGameEngine(GL11 gl)
	{
		if (mGameEngine != null)
		{
			mGameEngine.onDestroy(gl);
			mGameEngine = null;
			System.gc();
		}
		mGameEngine = mNewGameEngine;
		mNewGameEngine = null;
		mRenderEngine.setRootEngine(gl, mGameEngine.mRootEngine);
		mEglHelper.finish();
		needStartEgl = true;
	}

	private boolean needToWait()
	{
		return (mPaused || (!mHasFocus) || (!mHasSurface) || mContextLost) && (!mDone);
	}

	protected void surfaceCreated()
	{
		synchronized (this)
		{
			mHasSurface = true;
			mContextLost = false;
			notify();
		}
	}

	protected void surfaceDestroyed()
	{
		synchronized (this)
		{
			mRenderEngine.onDestroy(gl);
			mContextLost = true;
			mHasSurface = false;
			notify();
		}
	}

	protected void surfaceChanged(int width, int height)
	{
		synchronized (this)
		{
			mWidth = width;
			mHeight = height;
			AngleSurfaceView.mSizeChanged = true;
		}
	}

	protected void onPause()
	{
		synchronized (this)
		{
			Log.d("AngleRenderThread", "PAUSED!!!");
			mPaused = true;
			notify();
		}
	}

	protected void onResume()
	{
		synchronized (this)
		{
			Log.d("AngleRenderThread", "RESUMED!!!");
			mPaused = false;
			notify();
		}
	}

	protected void onWindowFocusChanged(boolean hasFocus)
	{
		synchronized (this)
		{
			mHasFocus = hasFocus;
			if (mHasFocus == true)
			{
				notify();
			}
		}
	}

	protected void requestExitAndWait()
	{
		if (!mDone)
		{
			synchronized (this)
			{
				mDone = true;
				notify();
			}
			try
			{
				join();
			}
			catch (InterruptedException ex)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	protected void setGameEngine(AngleAbstractGameEngine gameEngine)
	{
		mNewGameEngine = gameEngine;
	}
}
