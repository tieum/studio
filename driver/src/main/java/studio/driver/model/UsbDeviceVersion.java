/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.model;

public enum UsbDeviceVersion {

    DEVICE_VERSION_1, DEVICE_VERSION_2, DEVICE_VERSION_ANY;

    public boolean isV1() {
        return equals(DEVICE_VERSION_1) || equals(DEVICE_VERSION_ANY);
    }

    public boolean isV2() {
        return equals(DEVICE_VERSION_2) || equals(DEVICE_VERSION_ANY);
    }
}
