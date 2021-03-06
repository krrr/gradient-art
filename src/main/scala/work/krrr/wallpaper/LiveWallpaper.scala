package work.krrr.wallpaper

import scala.util.Random
import work.krrr.androidhelper.Conversions.funcAsRunnable
import GradientArtDrawable.Filter
import org.json.{JSONArray, JSONException}
import android.content.{Context, SharedPreferences}
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.drawable.{BitmapDrawable, TransitionDrawable}
import android.graphics.{Bitmap, Canvas, PixelFormat}
import android.os.{Handler, SystemClock}
import android.preference.PreferenceManager
import android.service.wallpaper.WallpaperService
import android.util.{DisplayMetrics, Log}
import android.view.View.MeasureSpec
import android.view.{LayoutInflater, SurfaceHolder, ViewGroup, WindowManager}
import android.widget.{RelativeLayout, TextView}
import android.widget.RelativeLayout.LayoutParams



class LiveWallpaper extends WallpaperService {
    private val handler = new Handler

    def onCreateEngine() = new GraEngine

    class GraEngine extends Engine with OnSharedPreferenceChangeListener {
        val aniDuration = 1000
        private val pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext)
        private val changeTask: Runnable = () => { doDrawingAnimated(); scheduleChangeTask() }
        private var aniRunnable: Runnable = null
        private var changeTaskNextRun = -1L
        private val (view, nameLabel, subLabel, gra) = {
            // inflate view and set text shadows
            val view = LayoutInflater.from(
                getApplicationContext).inflate(R.layout.main, null)
            val nameLabel = view.findViewById(R.id.gra_name).asInstanceOf[TextView]
            val subLabel = view.findViewById(R.id.gra_subname).asInstanceOf[TextView]
            val gra = view.findViewById(R.id.gra_view).asInstanceOf[GradientArtView].gra

            val metrics = new DisplayMetrics
            val wm = getSystemService(Context.WINDOW_SERVICE).asInstanceOf[WindowManager]
            wm.getDefaultDisplay.getMetrics(metrics)
            val radius = metrics.density * 2
            Log.d("LWPService", "Shadow radius: " + radius)
            nameLabel.setShadowLayer(radius, 0, 0, 0xEE111111)
            subLabel.setShadowLayer(radius, 0, 0, 0xEE222222)

            (view, nameLabel, subLabel, gra)
        }
        private var schemeSets: JSONArray = null
        private var schemeSetId = -1
        private val schemeSetMap = Array(R.raw.uigradients, R.raw.animegame)

        private def scheduleChangeTask(delay: Long = -1) {
            val _delay = if (delay == -1) pref.getString("period", "1800000").toLong else delay
            handler.postDelayed(changeTask, _delay)
            changeTaskNextRun = System.currentTimeMillis() + _delay
        }

        override def onSurfaceCreated(holder: SurfaceHolder) {
            holder.setFormat(PixelFormat.RGBA_8888)
            pref.registerOnSharedPreferenceChangeListener(this)
            // for some unknown reasons, onVisibilityChanged will be called three
            // times initially: show, hide, show. So readSettings first, then
            // first draw will be done in onSurfaceChanged
            fromSettings()
        }

        override def onSurfaceChanged(holder: SurfaceHolder, format: Int,
                                      width: Int, height: Int) {
            layoutView(width, height)
            doDrawing()
        }

        override def onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(changeTask)
            pref.unregisterOnSharedPreferenceChangeListener(this)
        }

        override def onVisibilityChanged(visible: Boolean) = visible match {
            // postDelayed will die in deep sleep. Here we record UTC time of every scheduled
            // changeTask and restore the state after becoming visible again.
            case true =>
                if (changeTaskNextRun > 0) {
                    val diff = changeTaskNextRun - System.currentTimeMillis
                    if (diff >= 0) {
                        scheduleChangeTask(diff)
                    } else {  // a change should have taken place while invisible, so do it now
                        val period = pref.getString("period", "1800000").toLong
                        val nextAniDelay = period - (-diff % period)
                        if (nextAniDelay < aniDuration) doDrawing()  // avoid two animations overlap
                        else doDrawingAnimated()
                        scheduleChangeTask(nextAniDelay)
                    }
                } else
                    scheduleChangeTask()
            case false =>
                handler.removeCallbacks(changeTask)
                // if there is animation running then let it go
        }

        private def layoutView(w: Int, h: Int) {
            setNameLabelsPos(h)
            view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }

        private def setNameLabelsPos(height: Int) {
            var params = new LayoutParams(nameLabel.getLayoutParams)
            var namePos = pref.getString("name_pos", "50").toInt

            nameLabel.measure(MeasureSpec.makeMeasureSpec(1, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(ViewGroup.LayoutParams.WRAP_CONTENT, MeasureSpec.UNSPECIFIED))
            var marginTop = (height/100F*namePos - nameLabel.getMeasuredHeight/2F).toInt

            params.setMargins(0, marginTop, 0, 0)  // horizontal margin reset
            params.addRule(RelativeLayout.CENTER_HORIZONTAL)
            nameLabel.setLayoutParams(params)
        }

        // no need to call fromSettings before calling this
        def doDrawingAnimated() {
            if (aniRunnable != null) {
                Log.d("LWPService", "animation already running!")  // though not likely
                handler.removeCallbacks(aniRunnable)
            }
            val (w, h) = (view.getWidth, view.getHeight)
            val before = Bitmap.createBitmap( w,h, Bitmap.Config.ARGB_8888)
            val after = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(new Canvas(before))
            fromSettings()
            view.draw(new Canvas(after))
            val a = new BitmapDrawable(getResources, before)
            val b = new BitmapDrawable(getResources, after)
            val td = new TransitionDrawable(Array(a, b))
            Array(a, b, td).foreach(_.setBounds(0, 0, w, h))
            td.startTransition(aniDuration)

            val startTime = SystemClock.uptimeMillis + 20  // +20 for unavoidable delay
            aniRunnable = () =>
                if (SystemClock.uptimeMillis - startTime <= aniDuration) {
                    val holder = getSurfaceHolder
                    val canvas = holder.lockCanvas()
                    if (canvas == null) return
                    handler.postDelayed(aniRunnable, 20)  // 50FPS
                    td.draw(canvas)
                    holder.unlockCanvasAndPost(canvas)
                } else {
                    Array(before, after).foreach(_.recycle())
                    System.gc()
                    aniRunnable = null
                }
            aniRunnable.run()
        }

        def doDrawing() {
            val holder = getSurfaceHolder
            val canvas = holder.lockCanvas()
            if (canvas != null) {
                view.draw(canvas)
                holder.unlockCanvasAndPost(canvas)
            }
        }

        def fromSettings() {
            // load color scheme set
            val newId = pref.getString("scheme_set", "0").toInt
            if (schemeSetId == -1 || schemeSetId != newId) {
                schemeSetId = newId
                val i_stream = getResources.openRawResource(schemeSetMap(newId))
                val json_s = io.Source.fromInputStream(i_stream).mkString
                schemeSets = try new JSONArray(json_s) catch { case e: JSONException => null }
            }

            // set color and filter randomly, set gradientDrawable and TextViews
            val idx = pref.getString("filter", "0").toInt
            gra.filter = Filter(if (idx == -1) Random.nextInt(Filter.maxId) else idx)
            try {
                val entry = schemeSets.getJSONObject(Random.nextInt(schemeSets.length))
                if (entry.has("color"))
                    gra.setColor(entry.getString("color"))
                else
                    gra.setColors(Array("color1", "color2").map(entry.getString))

                var name, subName = ""
                var namePos = pref.getString("name_pos", "50").toInt
                if (namePos != -1) {
                    name = entry.getString("name")
                    subName = if (entry.has("sub_name")) entry.getString("sub_name") else ""
                }
                nameLabel.setText(name)
                subLabel.setText(subName)
            } catch {
                case e@(_: JSONException | _: NullPointerException) =>
                    nameLabel.setText("Failed to parse JSON")
                    subLabel.setText(e.toString)
                    Log.e("LWPService", "JSON error: " + e)
            }
            layoutView(view.getWidth, view.getHeight)
        }

        def onSharedPreferenceChanged(pref: SharedPreferences, key: String) = key match {
            case "period" =>
                handler.removeCallbacks(changeTask)
                scheduleChangeTask()
            case _ =>
                // assume wallpaper is invisible now
                // force animated redrawing after become visible
                changeTaskNextRun = 1
        }
    }

}
