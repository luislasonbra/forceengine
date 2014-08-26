package com.eric.forceengine;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;

import com.eric.forceengine.objects.ColoredForceCircle;

import java.util.HashMap;
import java.util.Map;

import forceengine.objects.Force;
import forceengine.objects.ForceCircle;
import forceengine.objects.PointVector;
import forceengine.objects.RectVector;
import forceengine.objects.StaticCircle;
import forceengine.objects.StaticLine;
import forceengine.objects.Vector;
import forceengine.physics.PhysicsEngine;


public class ForceEngineActivity extends Activity implements View.OnTouchListener, SensorEventListener {

	private static final String TAG = ForceEngineActivity.class.getSimpleName();

	public static final long FRAME_DURATION = 16; // ms
	private static final float RADIUS = UiUtils.getPxFromDp(36);
	private static final float MASS = 100;
	private static final double RESTITUTION = 0.9;

	private static final double DRAG_SPRING_CONSTANT = 1.0 / 5.0;
	private static final double DRAG_FRICTION = 1.0;
	private static final long DRAG_MIN_TIME = 200; // ms
	private static final double SELECT_SLOP = UiUtils.getPxFromDp(10);

	private RenderThread mRenderThread;

	private PhysicsEngine mEngine;
	private ForceSurfaceView mForceSurface;
	private Vector mGravity;

	private SensorManager mSensorManager;
	private Sensor mSensor;

	private Map<Integer, Pair<PointVector, forceengine.objects.Point>> mDragging = new HashMap<Integer, Pair<PointVector, forceengine.objects.Point>>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_force_engine);

		// sensors
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

		// create engine
		Point size = new Point();
		getWindowManager().getDefaultDisplay().getSize(size);

		mGravity = new RectVector(0, 0);

		mEngine = new PhysicsEngine(size.x, size.y) {
			@Override
			public Vector accelerate(Force f, PointVector pv, double t) {
				Vector v = super.accelerate(f, pv, t);

				for (Pair<PointVector, forceengine.objects.Point> drag : mDragging.values()) {
					if (f == drag.first && drag.second != null) {
						v.add(new RectVector(
								(drag.second.getX() - f.getX()) * DRAG_SPRING_CONSTANT - f.getvx() * DRAG_FRICTION,
								(drag.second.getY() - f.getY()) * DRAG_SPRING_CONSTANT - f.getvy() * DRAG_FRICTION));
					}
				}

				return v.add(mGravity);
			}
		};
		mForceSurface = (ForceSurfaceView) findViewById(R.id.surface);

		mForceSurface.setOnTouchListener(this);

		if (mForceSurface != null) {
			mForceSurface.setEngine(mEngine);
		}

		mRenderThread = new RenderThread(mEngine, mForceSurface, new Handler());
		mRenderThread.run();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.force_engine, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_clear) {
			mEngine.clear();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	public PointVector isInsideCircle(float x, float y) {
		double minDistSq = -1;
		double distSq;
		PointVector point = null;

		for (ForceCircle forceCircle : mEngine.getForceCircles()) {
			distSq = forceengine.objects.Point.distanceSq(x, y, forceCircle.getX(), forceCircle.getY());
			if (distSq < Math.pow(RADIUS + SELECT_SLOP, 2)
					&& (minDistSq == -1 || distSq <= minDistSq)) {
				point = forceCircle;
				minDistSq = distSq;
			}
		}

		return point;
	}

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		final int action = event.getAction();

		switch (action & MotionEvent.ACTION_MASK) {
			case MotionEvent.ACTION_POINTER_DOWN:
			case MotionEvent.ACTION_DOWN:
				for (int i = 0; i < event.getPointerCount(); i++) {
					int id = event.getPointerId(i);

					if (!mDragging.containsKey(id)) {
						PointVector touching = isInsideCircle(event.getX(i), event.getY(i));

						if (touching != null) {
							mDragging.put(id, new Pair<PointVector, forceengine.objects.Point>(
									touching,
									new forceengine.objects.Point(event.getX(i), event.getY(i))
							));
						} else {
							mDragging.put(id, new Pair<PointVector, forceengine.objects.Point>(
									null,
									new forceengine.objects.Point(event.getX(i), event.getY(i))
							));
						}
					}
				}

				break;

			case MotionEvent.ACTION_MOVE:

				for (int i = 0; i < event.getPointerCount(); i++) {
					int id = event.getPointerId(i);

					if (mDragging.containsKey(id)) {
						Pair<PointVector, forceengine.objects.Point> dragging = mDragging.get(id);

						if (dragging.first != null && dragging.second != null) {
							dragging.second.setX(event.getX(i));
							dragging.second.setY(event.getY(i));
						}
					}
				}

				break;

			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_POINTER_UP:
			case MotionEvent.ACTION_CANCEL:

				// Extract the index of the pointer that left the touch sensor
				final int pointerIndex = (action & MotionEvent.ACTION_POINTER_INDEX_MASK)
						>> MotionEvent.ACTION_POINTER_INDEX_SHIFT;

				int id = event.getPointerId(pointerIndex);

				if (mDragging.containsKey(id)) {

					Pair<PointVector, forceengine.objects.Point> dragging = mDragging.get(id);

					if (dragging.first == null && dragging.second != null &&
							event.getEventTime() - event.getDownTime() < DRAG_MIN_TIME) {
						long time = event.getEventTime() - event.getDownTime();

						mEngine.addForceCircle(new ColoredForceCircle(
								event.getX(pointerIndex), event.getY(pointerIndex),
								(event.getX(pointerIndex) - dragging.second.getX()) / time * FRAME_DURATION,
								(event.getY(pointerIndex) - dragging.second.getY()) / time * FRAME_DURATION,
								RADIUS, MASS, RESTITUTION, UiUtils.randomColor(mEngine)));
					}

					mDragging.remove(id);
					break;
				}

				break;
		}

		return true;
	}

	@Override
	protected void onResume() {
		super.onResume();
		mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_NORMAL);
	}

	@Override
	protected void onPause() {
		super.onPause();
		mSensorManager.unregisterListener(this);
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		if (event.values != null) {
			mGravity.setvx(-event.values[0]);
			mGravity.setvy(event.values[1]);
		}
	}
}