class ArcoreGeospatialPose {
  double? latitude;
  double? longitude;
  double? altitude;
  List<double>? eastUpSouthQuaternion;
  double? horizontalAccuracy;
  double? orientationYawAccuracy;
  double? verticalAccuracy;

  ArcoreGeospatialPose.fromMap(Map<dynamic, dynamic> map) {
    this.latitude = map['latitude'];
    this.longitude = map['longitude'];
    this.altitude = map['altitude'];
    this.eastUpSouthQuaternion = map['eastUpSouthQuaternion'];
    this.horizontalAccuracy = map['horizontalAccuracy'];
    this.orientationYawAccuracy = map['orientationYawAccuracy'];
    this.verticalAccuracy = map['verticalAccuracy'];
  }
}