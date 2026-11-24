package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.link.WatchLink

/** The long transfers of one link (LifeLog, workouts, tide): their state is this watch's alone. Made only by its [WatchLink]. */
@RequiresApi(Build.VERSION_CODES.O)
class LinkTransfers internal constructor(link: WatchLink) {
    val workouts: H5600WorkoutReader = H5600WorkoutReader(link)

    val lifeLog: H5600LifeLogReader = H5600LifeLogReader(link, workouts)

    /** The tide record sent to this link's watch. */
    val tide: H5600TideSender = H5600TideSender(link)

    /** True while a LifeLog or workout transfer runs on this link. */
    val busy: Boolean get() = lifeLog.isActive || workouts.isActive
}
