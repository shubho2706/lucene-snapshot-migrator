#!/usr/bin/env python3
"""
NYC Taxi Trip Dataset Generator
================================
Generates a deterministic, reproducible NYC taxi-like dataset for end-to-end
testing of the Lucene Snapshot Converter.

Dataset fields exercise a range of Lucene/Solr field types:
  - id            : unique string key
  - medallion     : keyword (taxi medallion ID)
  - vendor_id     : keyword (short enum: CMT, VTS, DDS)
  - pickup_datetime, dropoff_datetime : date (ISO 8601)
  - passenger_count : integer
  - trip_time_secs  : integer (long)
  - trip_distance   : float
  - pickup_latitude, pickup_longitude   : float (geo)
  - dropoff_latitude, dropoff_longitude : float (geo)
  - rate_code     : integer (enum-like: 1-5)
  - payment_type  : keyword (CSH, CRD, NOC, DIS)
  - fare_amount, surcharge, tip_amount, tolls_amount, total_amount : float
  - store_and_fwd_flag : boolean (as string Y/N for Solr compatibility)
  - trip_description   : text (free-form, for full-text search testing)

Usage:
  python3 generate-taxi-data.py [num_records] [seed] [output_file]
  python3 generate-taxi-data.py 2000 42 taxi-data.json
  python3 generate-taxi-data.py                          # defaults: 2000 records, seed=42, stdout

Output: JSON array suitable for Solr bulk /update endpoint.
"""
import json
import random
import sys
from datetime import datetime, timedelta


# Medallion pool (deterministic fake IDs)
MEDALLION_POOL = [
    f"{chr(65 + i)}{j:03d}" for i in range(26) for j in range(1, 40)
]

VENDOR_IDS = ["CMT", "VTS", "DDS"]
PAYMENT_TYPES = ["CSH", "CRD", "NOC", "DIS"]
RATE_CODES = [1, 2, 3, 4, 5]

# NYC bounding box (Manhattan-centric with some outer borough spread)
NYC_LAT = (40.630, 40.850)
NYC_LON = (-74.050, -73.750)

# Trip description templates (for full-text search testing)
TRIP_TEMPLATES = [
    "Pickup near {pickup}, dropoff near {dropoff}. {weather} conditions.",
    "Short trip from {pickup} to {dropoff}. Passenger seemed in a hurry.",
    "Long ride through {pickup} area heading toward {dropoff}. {weather} evening.",
    "Airport transfer from {pickup}. Destination: {dropoff} terminal area.",
    "Late night ride. Picked up at {pickup}, dropped at {dropoff}.",
    "Morning commute run from {pickup} to {dropoff}. Heavy traffic on {road}.",
    "Tourist trip: {pickup} sightseeing loop ending at {dropoff}.",
    "Business district transfer: {pickup} to {dropoff}. {weather} day.",
]

NEIGHBORHOODS = [
    "Midtown", "SoHo", "Tribeca", "Chelsea", "East Village", "West Village",
    "Upper East Side", "Upper West Side", "Harlem", "Financial District",
    "Chinatown", "Little Italy", "Gramercy", "Murray Hill", "Hell's Kitchen",
    "Times Square", "Penn Station", "Grand Central", "Brooklyn Heights",
    "Williamsburg", "DUMBO", "Park Slope", "Astoria", "Long Island City",
    "JFK Airport", "LaGuardia Airport", "Newark Airport",
]

WEATHER_CONDITIONS = ["Clear", "Rainy", "Overcast", "Snowy", "Foggy", "Humid", "Windy"]
ROADS = ["FDR Drive", "West Side Highway", "Broadway", "5th Avenue", "Park Avenue",
         "BQE", "Williamsburg Bridge", "Brooklyn Bridge", "Holland Tunnel"]


def generate_dataset(num_records=2000, seed=42):
    """Generate deterministic NYC taxi dataset."""
    rng = random.Random(seed)

    base_date = datetime(2024, 1, 1)
    records = []

    for i in range(num_records):
        # Time
        pickup_dt = base_date + timedelta(
            days=rng.randint(0, 364),
            hours=rng.randint(0, 23),
            minutes=rng.randint(0, 59),
            seconds=rng.randint(0, 59),
        )
        trip_time = rng.randint(60, 3600)
        dropoff_dt = pickup_dt + timedelta(seconds=trip_time)

        # Distance and fare
        trip_distance = round(rng.uniform(0.1, 25.0), 2)
        fare = round(2.50 + trip_distance * 2.5 + rng.uniform(-1, 3), 2)
        fare = max(fare, 2.50)  # minimum fare
        surcharge = round(rng.choice([0.0, 0.0, 0.5, 0.5, 1.0]), 2)
        tip = round(rng.uniform(0, fare * 0.30), 2) if rng.random() > 0.3 else 0.0
        tolls = round(rng.choice([0.0, 0.0, 0.0, 0.0, 4.80, 5.54, 11.08]), 2)
        total = round(fare + surcharge + tip + tolls, 2)

        # Geo
        pickup_lat = round(rng.uniform(*NYC_LAT), 6)
        pickup_lon = round(rng.uniform(*NYC_LON), 6)
        dropoff_lat = round(rng.uniform(*NYC_LAT), 6)
        dropoff_lon = round(rng.uniform(*NYC_LON), 6)

        # Description
        pickup_hood = rng.choice(NEIGHBORHOODS)
        dropoff_hood = rng.choice(NEIGHBORHOODS)
        weather = rng.choice(WEATHER_CONDITIONS)
        road = rng.choice(ROADS)
        template = rng.choice(TRIP_TEMPLATES)
        description = template.format(
            pickup=pickup_hood, dropoff=dropoff_hood,
            weather=weather, road=road
        )

        record = {
            "id": f"taxi_{i + 1:05d}",
            "medallion": rng.choice(MEDALLION_POOL),
            "vendor_id": rng.choice(VENDOR_IDS),
            "rate_code": rng.choice(RATE_CODES),
            "pickup_datetime": pickup_dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "dropoff_datetime": dropoff_dt.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "passenger_count": rng.randint(1, 6),
            "trip_time_secs": trip_time,
            "trip_distance": trip_distance,
            "pickup_latitude": pickup_lat,
            "pickup_longitude": pickup_lon,
            "dropoff_latitude": dropoff_lat,
            "dropoff_longitude": dropoff_lon,
            "payment_type": rng.choice(PAYMENT_TYPES),
            "fare_amount": fare,
            "surcharge": surcharge,
            "tip_amount": tip,
            "tolls_amount": tolls,
            "total_amount": total,
            "store_and_fwd_flag": rng.choice(["Y", "N", "N", "N"]),
            "trip_description": description,
        }
        records.append(record)

    return records


def print_stats(records):
    """Print dataset summary statistics to stderr."""
    total_fare = sum(r["total_amount"] for r in records)
    avg_dist = sum(r["trip_distance"] for r in records) / len(records)
    vendors = {}
    for r in records:
        vendors[r["vendor_id"]] = vendors.get(r["vendor_id"], 0) + 1
    payments = {}
    for r in records:
        payments[r["payment_type"]] = payments.get(r["payment_type"], 0) + 1

    sys.stderr.write(f"\nDataset Statistics:\n")
    sys.stderr.write(f"  Total records   : {len(records)}\n")
    sys.stderr.write(f"  Total fare      : ${total_fare:,.2f}\n")
    sys.stderr.write(f"  Avg distance    : {avg_dist:.2f} miles\n")
    sys.stderr.write(f"  Vendors         : {vendors}\n")
    sys.stderr.write(f"  Payment types   : {payments}\n")
    sys.stderr.write(f"  Date range      : {records[0]['pickup_datetime']} to {records[-1]['pickup_datetime']}\n")

    # Estimate JSON size
    json_str = json.dumps(records)
    sys.stderr.write(f"  JSON size       : {len(json_str):,} bytes ({len(json_str) / 1024:.1f} KB)\n\n")


if __name__ == "__main__":
    num = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 42
    outfile = sys.argv[3] if len(sys.argv) > 3 else None

    records = generate_dataset(num, seed)
    print_stats(records)

    if outfile:
        with open(outfile, "w") as f:
            json.dump(records, f)
        sys.stderr.write(f"Written to {outfile}\n")
    else:
        json.dump(records, sys.stdout)
