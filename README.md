# Hubitat-MySubaru

### Links your MySubaru STARLINK-connected Subaru to Hubitat: lock/unlock, horn/lights, remote start/stop, locate, EV charge-now, and vehicle status.

Subaru does not publish a public API. This talks to the same undocumented
endpoints the official MySubaru mobile app uses, modeled on the
reverse-engineering done by the [subarulink](https://github.com/G-Two/subarulink)
project (which also powers Home Assistant's official Subaru integration). It
can change or break without warning if Subaru changes their backend.

## Requirements

- Hubitat Elevation hub
- MySubaru account with at least one vehicle enrolled
- Active MySubaru Security Plus/Remote subscription (required for lock/unlock,
  horn/lights, remote start/stop, and locate - basic odometer/fuel status
  works without it)
- Your 4-digit remote services PIN (set in the MySubaru app under Account)

## Install

- On GitHub, choose `Hubitat-MySubaru-Driver.groovy`, click **Raw**, and copy the entire text
- On the Hubitat hub, expand the **FOR DEVELOPERS** menu and select **Drivers Code**
- Select **+ Add driver**, paste the copied text, and select **Save**
- On GitHub, choose `Hubitat-MySubaru-App.groovy`, click **Raw**, and copy the entire text
- On the Hubitat hub, select **Apps Code**
- Select **+ Add app**, paste the copied text, and select **Save**

Or install both via [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/).

## Configure

- On the Hubitat hub, go to **Apps** and select **Add User App**
- Choose **Subaru Connect**
- Enter your MySubaru username/password, remote services PIN, and country
- Select **Connect**
- The first time you connect from a new Hubitat install, MySubaru requires
  two-factor verification - choose email or SMS, request a code, and enter
  it when prompted
- Once your vehicle(s) appear, select **Done** to create a Hubitat device
  for each one

## What you get

Each vehicle becomes its own device with commands for lock, unlock (all
doors, driver door only, or tailgate only), horn + lights, lights only,
locate, remote start (using a named climate preset already saved in the
MySubaru app), remote stop, EV charge-now, and refresh - plus attributes for
odometer, fuel percent, tire pressures, door/window/lock states, GPS
location, and EV battery/charging status, refreshed on a configurable
interval.

## Notes

- Repeated bad PIN attempts can lock your MySubaru account, so remote
  commands are automatically disabled after one rejected PIN until it's
  corrected in the app settings
- Status refresh only pulls data already cached on Subaru's servers; it does
  not wake up the car or drain its battery
