# WanXiang Logo assets

- `wanxiang-logo.svg`: transparent-background vector mark.
- `app/src/main/res/drawable/wanxiang_splash_animated.xml`: Android `AnimatedVectorDrawable` used by the system SplashScreen.

The SplashScreen uses a transparent canvas and a black mark. Set the SplashScreen background separately (for example, white in light theme and a dark surface in dark theme). The icon scales in and rotates into place without delaying `MainActivity`.
