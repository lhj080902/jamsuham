package kr.jamsuham.fastpack;

/** DownloadedPackSourceMixin 이 구현한다. 설정 창에서 남겨 둔 팩을 확인·정리할 때 쓴다 */
public interface PackKeeper {
    /** 남겨 둔(지금 서버에서 쓰지 않는) 서버 팩이 있으면 그 서버 주소, 없으면 null */
    String fastpack$keptServer();

    /** 남겨 둔 팩을 내리고 리소스를 다시 읽는다. 정리했으면 true. 메인 스레드에서 부른다 */
    boolean fastpack$dropKeptNow();

    /** 지금 들고 있는 DownloadedPackSource (게임이 만들 때 잡아 둠) */
    final class Holder {
        public static volatile PackKeeper instance;
        private Holder() {}
    }
}
