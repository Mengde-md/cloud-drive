package com.mengde.clouddrive;

public final class CloudDriveApplication {

    static final String PROJECT_NAME = "cloud-drive-learning";
    static final int REQUIRED_JAVA_FEATURE = 17;

    private CloudDriveApplication() {
    }

    public static void main(String[] args) {
        System.out.println(startupBanner());
    }

    static String startupBanner() {
        return PROJECT_NAME + " Day01 bootstrap: Java " + REQUIRED_JAVA_FEATURE;
    }
}
