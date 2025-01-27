class ArcoreGeospatialPose {
  double? latitude;
  double? longitude;
  double? altitude;
  List<double>? quaternion;

  ArcoreGeospatialPose.fromMap(Map<dynamic, dynamic> map) {
    this.latitude = map['latitude'];
    this.longitude = map['longitude'];
    this.altitude = map['altitude'];
    this.quaternion = map['quaternion'];
  }
}