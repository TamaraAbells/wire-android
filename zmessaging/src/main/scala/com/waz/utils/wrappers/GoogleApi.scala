/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.utils.wrappers

import android.app.Activity
import com.google.android.gms.common.ConnectionResult._
import com.google.android.gms.common.{ConnectionResult, GoogleApiAvailability}
import com.google.firebase.iid.FirebaseInstanceId
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.{info, warn}
import com.waz.content.GlobalPreferences
import com.waz.content.GlobalPreferences.GPSErrorDialogShowCount
import com.waz.model.PushToken
import com.waz.service.BackendConfig
import com.waz.utils.LoggedTry
import com.waz.utils.events.Signal

import scala.util.Try

trait GoogleApi {
  def isGooglePlayServicesAvailable: Signal[Boolean]
  def checkGooglePlayServicesAvailable(activity: Activity): Unit
  def onActivityResult(requestCode: Int, resultCode: Int): Unit
  def getPushToken: Option[PushToken]
  def deleteAllPushTokens(): Unit
}

class GoogleApiImpl(context: Context, beConfig: BackendConfig, prefs: GlobalPreferences) extends GoogleApi {

  import GoogleApi._

  private val api = GoogleApiAvailability.getInstance()

  private val firebaseApp = beConfig.firebaseOptions(context)

  private def isGPSAvailable = Try(api.isGooglePlayServicesAvailable(context) == SUCCESS).toOption.contains(true)

  override val isGooglePlayServicesAvailable = Signal[Boolean](isGPSAvailable)

  override def checkGooglePlayServicesAvailable(activity: Activity) = api.isGooglePlayServicesAvailable(activity) match {
    case SUCCESS =>
      info("Google Play Services available")
      isGooglePlayServicesAvailable ! true
    case SERVICE_VERSION_UPDATE_REQUIRED if prefs.getFromPref(GPSErrorDialogShowCount) <= MaxErrorDialogShowCount =>
      info(s"Google Play Services requires update - prompting user")
      prefs.preference(GPSErrorDialogShowCount).mutate(_ + 1)
      api.getErrorDialog(activity, SERVICE_VERSION_UPDATE_REQUIRED, RequestGooglePlayServices).show()
    case code =>
      isGooglePlayServicesAvailable ! false
      warn(s"Google Play Services not available: error code: $code")
  }

  override def onActivityResult(requestCode: Int, resultCode: Int) = requestCode match {
    case RequestGooglePlayServices =>
      info(s"Google Play Services request completed, result: $resultCode")
      //It's quite natural for the user to click the back button after updating GPS, which results in a ACTIVITY_CANCELLED
      //result code. So just check if the services are available again on any result.
      isGooglePlayServicesAvailable ! isGPSAvailable
    case _ => //
  }

  override def getPushToken =
    firebaseApp.flatMap(app => LoggedTry(FirebaseInstanceId.getInstance(app).getToken(beConfig.pushSenderId, "FCM")).toOption.map(PushToken(_)))

  override def deleteAllPushTokens(): Unit =
    firebaseApp.foreach(app => LoggedTry(FirebaseInstanceId.getInstance(app).deleteInstanceId()))
}

object GoogleApi {
  val RequestGooglePlayServices = 7976
  val MaxErrorDialogShowCount = 3
}
