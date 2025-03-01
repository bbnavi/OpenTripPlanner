package org.opentripplanner.routing.core;

import org.junit.Ignore;
import org.junit.Test;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.api.model.TripPlan;
import org.opentripplanner.api.resource.GraphPathToTripPlanConverter;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.impl.GraphPathFinder;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.standalone.Router;
import org.opentripplanner.util.TestUtils;

import java.util.List;

import static org.opentripplanner.routing.core.PolylineAssert.assertThatPolylinesAreEqual;

public class CarRoutingTest {

    static Graph ordinaryHerrenbergGraph() { return TestGraphBuilder.buildGraph(ConstantsForTests.HERRENBERG_OSM); }
    static Graph hindenburgStrUnderConstruction() { return TestGraphBuilder.buildGraph(ConstantsForTests.HERRENBERG_HINDENBURG_UNDER_CONSTRUCTION_OSM); };
    static long dateTime = TestUtils.dateInSeconds("Europe/Berlin", 2020, 03, 2, 7, 0, 0);

    private static String computePolyline(Graph graph, GenericLocation from, GenericLocation to) {
        RoutingRequest request = new RoutingRequest();
        request.dateTime = dateTime;
        request.from = from;
        request.to = to;

        request.modes = new TraverseModeSet(TraverseMode.CAR, TraverseMode.WALK);

        request.setNumItineraries(5);
        request.setRoutingContext(graph);

        GraphPathFinder gpf = new GraphPathFinder(new Router(graph.routerId, graph));
        List<GraphPath> paths = gpf.graphPathFinderEntryPoint(request);

        TripPlan plan = GraphPathToTripPlanConverter.generatePlan(paths, request);
        return plan.itinerary.get(0).legs.get(0).legGeometry.getPoints();
    }

    @Test
    public void routeToHighwayTrack() {
        GenericLocation seeStrasse = new GenericLocation(48.59724504108028,8.868606090545656);
        GenericLocation offTuebingerStr = new GenericLocation(48.58529481682537, 8.888196945190431);

        var polyline = computePolyline(ordinaryHerrenbergGraph(), seeStrasse, offTuebingerStr);

        assertThatPolylinesAreEqual(polyline, "usrgHyccu@d@bAl@jAT\\JLFHNNLJJHJFLHNJLDPDT?NAN?FANCFAB?JADGDIFKDINULSHONa@FUJ_@Jo@DSBQJw@@[Bo@Di@D{@D_ATsDDu@Am@Ee@AUEi@Eu@C{@Bo@@IJYTi@HQT]T]\\e@|A}BZc@FKZg@P]vBgETa@j@cAr@uAv@}ANYVe@Xm@d@}@Ra@Vg@LWTa@`@y@`@w@b@w@Xi@Xg@Zi@RYR]Zg@f@u@X_@V_@r@aAp@y@f@s@h@o@b@k@V[V[X_@Xa@Va@V_@Ta@Ta@R_@Zm@Vk@j@qABGHUN_@Na@Xw@Vu@b@wANk@Pm@z@_DRu@");
    }

    @Test
    public void shouldNotRouteAcrossParkingLot() {
        var nagolderStr = new GenericLocation(48.59559, 8.86472);
        var horberstr = new GenericLocation(48.59459, 8.86647);

        var polyline = computePolyline(ordinaryHerrenbergGraph(), nagolderStr, horberstr);

        assertThatPolylinesAreEqual(polyline, "mirgHmkbu@Au@@m@?s@@cF@g@?k@@M@MBKBIHAF?D@FDB@HDFJHJLPHJJJj@n@PLLJHF");
    }

    @Test
    public void shouldAvoidResidentialStreet() {
        var nagolderStr = new GenericLocation(48.59571, 8.86581);
        var zeppelinStr = new GenericLocation(48.59985,8.86537);

        var polyline = computePolyline(hindenburgStrUnderConstruction(), nagolderStr, zeppelinStr);

        assertThatPolylinesAreEqual(polyline, "}irgHgrbu@?bA?zB@XBnCInB]jFGrAGdAC|AU?eAFg@C}@Ks@CGAY?YD]Fm@X_@Pw@d@eAn@k@VM@]He@Fo@Bi@??c@?Q@gD?Q?Q@mD?y@?aD?eCAw@EiBKgB");
    }

    @Test
    public void shouldBeAbleToTurnIntoAufDemGraben() {
        var gueltsteinerStr = new GenericLocation(48.59240, 8.87024);
        var aufDemGraben = new GenericLocation(48.59487, 8.87133);

        var polyline = computePolyline(hindenburgStrUnderConstruction(), gueltsteinerStr, aufDemGraben);

        assertThatPolylinesAreEqual(polyline, "ouqgH}mcu@gAE]U}BaA]Q}@]uAs@[SAm@Ee@AUEi@XEQkBQ?Bz@Dt@Dh@@TGBC@KBSHGx@");
    }

    @Test
    public void shouldTakeDetoursInAlzentalIntoAccount() {
        var nagolderStr = new GenericLocation(48.59559, 8.86472);
        var aufDemGraben = new GenericLocation(48.59487, 8.87133);

        var polyline1 = computePolyline(hindenburgStrUnderConstruction(), nagolderStr, aufDemGraben);
        assertThatPolylinesAreEqual(polyline1, "mirgHmkbu@Au@@m@?s@@cF@g@?k@@M@MBKBIHAF?D@FDB@HDFJHJLPHJJJj@n@PLLJNLPNp@p@NNNPJNDHFLHTTdA^zADNDLFH@BBDJLDBDBDBH@B@F?X@X@p@BfB@v@?lA@D?xA@b@?Z@h@?d@?[}D[cD[uD]qDYcDEWg@{FkAEmAIB]I]G_@s@yEa@kCSmAOcA?GAEi@~@c@j@{@r@c@Re@JC@o@LcBXQkBQ?Bz@Dt@Dh@@TGBC@KBSHGx@");

        var polyline2 = computePolyline(hindenburgStrUnderConstruction(), aufDemGraben, nagolderStr);
        assertThatPolylinesAreEqual(polyline2, "}drgHytcu@Fy@RIJCBAFCDd@@l@ZRtAr@|@\\\\P|B`A\\T?dDHn@l@hE?BZlCVjCV`CFv@BZBXHr@@PBTBP@N?N?J@L?bAw@?gBAq@CYAYAG?CAIAECECECKMCEACGIEMEO_@{AUeAIUGMEIKOOQOOq@q@QOOMMKQMk@o@KKIKMQIKGKKMIGCEGCGAA?EAG@EJA@CHALAJ?R?T?VA^?h@A`E?zB@XBnCInB]jFGrAGdAC|APIBuABaAb@uGBe@D_A?_AAaB?c@");
    }

    @Test
    public void shouldNotGoThroughAlzentalWhenRoutingLongerDistance() {
        var nagolderStr = new GenericLocation(48.59559, 8.86472);
        var affstädt = new GenericLocation(48.6065, 8.8574);
        var autobahn = new GenericLocation(48.5814, 8.8968);
        var seestr = new GenericLocation(48.60158, 8.87313);
        var aufDemGraben = new GenericLocation(48.59487, 8.87133);

        var polyline1 = computePolyline(hindenburgStrUnderConstruction(), nagolderStr, autobahn);
        assertThatPolylinesAreEqual(polyline1, "mirgHmkbu@Au@@m@?s@@cF@g@?k@@M@MBKBIHAF?D@FDB@HDFJHJLPHJJJj@n@PLLJNLPNp@p@NNNPJNDHFLHTTdA^zADNDLFH@BBDJLDBDBDBH@B@F?X@X@p@BfB@v@?lA@D?xA@b@?Z@h@?d@?XCTCVCXA`AKdAKbCStBSxAKv@Ez@Ax@Dd@Nx@^dAdAr@nAR`@h@rAl@xBRl@HXf@n@b@Vj@PjAHj@@N?bA@`@?`@EXG`@OjAi@t@UhAYnBa@lB_@tBSZCtGk@xAO|BUrCUl@?h@Bh@FrAPNFPFLDHDF@DB@FBF@BBDBBB@F@F?DCDCBEBEBI@G?K?GAICGCG@W@UB]BWBMDYFYHg@FUDSFUDSFQFOHUHSHWRe@`@_APe@Rg@Pa@Nc@Rm@Tm@Ro@Rm@Ro@Pq@Rq@Pq@^wA^uA^wA^uALi@J]Pq@Rs@h@gBNg@Ne@Pe@Tm@Xy@Zw@\\y@Zu@\\s@NYPa@Ta@Ra@NWXg@Zi@Zg@Xg@LQn@cAVa@T_@d@q@f@s@PYRYPYR[NYNYLWN_@N[HWJ[J]La@H]F]Ha@D[D[BWD_@Da@B_@@]B]@[?W@W?Y?Y?o@A[?WA_@Ca@C]Gi@Go@Ie@Ki@Ie@Mm@G]G]G]G]E]Ee@CYC]Cc@A]Aa@A]?]?a@?a@@]@a@@_@F}@Fw@Bi@Ba@Be@@W?[@c@?e@AY?_@A_@C_@C_@Ca@C[E_@Ga@G]E[I]G[K_@IYIYK[M[M[MYMWQ]S[QWOSOSOOOOOMOOOMMSSWEIAGAGAECECECCCAEAC?E?E@CBCDSCQAYEQIQIg@Sc@Sa@Qi@Si@Sg@QUKg@Oi@Qi@Og@Qe@Mc@KQEUGQEQEe@Ke@KWEUESCKAIAUAO?KAK?K@O?K@U@OBSD{A\\eAT_Dz@gC~@aA^_@LI@I@I@IBI?E?CECCCCCAE?E?EBCBEDADCF?FKDEBOHIDIHQDQBOBOBUBM@Q@Q@MAOAI?SEWESEeAWi@OWI_@MICME[Ks@WmAe@OGSIOIOISKOKMKKIWYQ_@EKCE?EAI?KBa@Nc@Nm@Rq@H]Jg@VkARgARkAJg@Fe@Fg@Fa@");

        var polyline2 = computePolyline(hindenburgStrUnderConstruction(), autobahn, nagolderStr);
        assertThatPolylinesAreEqual(polyline2, "{pogHathu@G`@Gf@Gd@Kf@SjASfAWjAKf@I\\Sp@Ol@Ob@CJKZEN?HBNBJDDPNB@b@RJHLJNJRJNHNHRHNFlAd@r@VZJLDHB^LVHh@NdAVRDVDRDH?N@L@PAPALATCNCNCPCPEH?J?NCF?B?H?@DDDBBD@D?DABCDCBE@G@E@GFGDCFCHGHEFK^M`A_@fC_A~C{@dAUzA]RENCTAJAN?JAJ?J@N?T@H@J@RBTDVDd@Jd@JPDPDTFPDb@Jd@Lf@Ph@Nh@Pf@NTJf@Ph@Rh@R`@Pb@Rf@RPHPHXXHFHHDD@F?FBD@DBDDDBBD@D?FADCDEN@N@PFNLNNNLNNNNNRNRPVRZP\\LVLXLZLZJZHXHXJ^FZH\\DZF\\F`@D^BZB`@B^B^@^?^@X?d@Ab@?ZAVCd@C`@Ch@Gv@G|@A^A`@A\\?`@?`@?\\@\\@`@@\\Bb@B\\BXDd@D\\F\\F\\F\\F\\Ll@Hd@Jh@Hd@Fn@Fh@B\\B`@@^?V@Z?n@?X?XAV?VAZC\\A\\C^E`@E^CVEZEZI`@G\\I\\M`@K\\KZIVOZO^MVOXOXSZQXSXQXg@r@e@p@U^W`@o@bAMPYf@[f@[h@Yf@OVS`@U`@Q`@OX]r@[t@]x@[v@Yx@Ul@Qd@Od@Of@i@fBSr@Qp@K\\Mh@_@tA_@vA_@tA_@vAQp@Sp@Qp@Sn@Sl@Sn@Ul@Sl@Ob@Q`@Sf@Qd@a@~@Sd@IVIRITGNGPERGTERGTIf@GXEXCLGRGPIVEPCHE?EBCBEFADK@C?O@U?U?sAQi@Gi@Cm@?sCT}BTyANuGj@[BuBRmB^oB`@iAXu@TkAh@a@NYFa@Da@?cAAO?k@AkAIk@Qc@Wg@o@IYSm@m@yBi@sASa@s@oAeAeAy@_@e@Oy@E{@@w@DyAJuBRcCReAJaAJY@WBUBYBe@?i@?[Ac@?yAAE?mAAw@?gBAq@CYAYAG?CAIAECECECKMCEACGIEMEO_@{AUeAIUGMEIKOOQOOq@q@QOOMMKQMk@o@KKIKMQIKGKKMIGCEGCGAA?EAG@EJA@CHALAJ?R?T?VA^?h@A`E?zB@XBnCInB]jFGrAGdAC|APIBuABaAb@uGBe@D_A?_AAaB?c@");

        var polyline3 = computePolyline(hindenburgStrUnderConstruction(), autobahn, seestr);
        assertThatPolylinesAreEqual(polyline3, "{pogHathu@@EFi@Fk@NyAB_@JoAJ}AD_AH_BFeCNq@BIBGBGBGJMl@AX?X?Z?X@Z@TFPHNPLRFTBV@VAVCPGRIPMNONKFODWR_E`@iAJ_BNuFh@oFh@wFh@kFf@wBR}@Ju@Ha@DsALsAJqALqALmALqAL_BN{AP_Gh@cFf@cBN_CT_CR_AH_AHm@Fo@Fo@Bm@De@By@F{@FaAD{@D}@D]Bq@@s@D[Bc@@_@@aA@_A@]@c@?_A@c@?_@?IAkEYeBKcBOwBW}B[mB_@sAYqAc@eAa@aAc@m@Yo@[mAq@yBoAuBwAcBqAqAiAqAcAyBqBy@}@a@e@Y[yBmC}AsBwDcFyC_E{CeEm@w@q@{@s@{@kAuAs@w@s@w@mAqAs@s@s@q@y@u@i@e@iB{AgBwAaDwBmAu@i@[eCoAgCoAkBy@w@[oCgAwCgAcEqAaEmAcEkA_EgAyCy@{Cy@iCs@yCeAaAMuAWmDk@iF_AcHaAeBW_@Eu@Is@Iy@Ky@Y_@UUQQUKQK[Gc@A_@De@Ja@P]RSZIV?RDPLRXTd@NZLb@ZzAh@rJdA`TX`GDj@TvEj@jKJzAPvAR|@DPl@rB^|@b@|@j@t@`Av@h@XbC~AxA~@rHxEjEnCrBfBpFpFlHfIJJ@BvAvAx@p@v@f@|@^nA`@v@Hx@Hx@?hAEp@EpAEt@AhBDb@Hj@HnAZ^RnA~@\\VvAvAZ\\\\b@LZLZ\\v@dAzD~AhIfBfId@rAh@~Ap@lBzDtKj@tAvAnDl@|Av@bATXdAx@`CvAh@Vj@THDj@VrAf@hAb@j@^bBbArAxAjCrDhCpDDJvAbB~CvCTRNNVV`HfHxD|DrDtEx@bAzBpBh@b@NLzAp@`A\\n@RhA\\dAXLDvFpA");

        var polyline4 = computePolyline(hindenburgStrUnderConstruction(), autobahn, affstädt);
        assertThatPolylinesAreEqual(polyline4, "{pogHathu@G`@Gf@Gd@Kf@SjASfAWjAKf@I\\Sp@Ol@Ob@CJKZEN?HBNBJDDPNB@b@RJHLJNJRJNHNHRHNFlAd@r@VZJLDHB^LVHh@NdAVRDVDRDH?N@L@PAPALATCNCNCPCPEH?J?NCF?B?H?@DDDBBD@D?DABCDCBE@G@E@GFGDCFCHGHEFK^M`A_@fC_A~C{@dAUzA]RENCTAJAN?JAJ?J@N?T@H@J@RBTDVDd@Jd@JPDPDTFPDb@Jd@Lf@Ph@Nh@Pf@NTJf@Ph@Rh@R`@Pb@Rf@RPHPHXXHFHHDD@F?FBD@DBDDDBBD@D?FADCDEN@N@PFNLNNNLNNNNNRNRPVRZP\\LVLXLZLZJZHXHXJ^FZH\\DZF\\F`@D^BZB`@B^B^@^?^@X?d@Ab@?ZAVCd@C`@Ch@Gv@G|@A^A`@A\\?`@?`@?\\@\\@`@@\\Bb@B\\BXDd@D\\F\\F\\F\\F\\Ll@Hd@Jh@Hd@Fn@Fh@B\\B`@@^?V@Z?n@?X?XAV?VAZC\\A\\C^E`@E^CVEZEZI`@G\\I\\M`@K\\KZIVOZO^MVOXOXSZQXSXQXg@r@e@p@U^W`@o@bAMPYf@[f@[h@Yf@OVS`@U`@Q`@OX]r@[t@]x@[v@Yx@Ul@Qd@Od@Of@i@fBSr@Qp@K\\Mh@_@tA_@vA_@tA_@vAQp@Sp@Qp@Sn@Sl@Sn@Ul@Sl@Ob@Q`@Sf@Qd@a@~@Sd@IVIRITGNGPERGTERGTIf@GXEXCLGRGPIVEPCHE?EBCBEFADK@C?O@U?U?sAQi@Gi@Cm@?sCT}BTyANuGj@[BuBRmB^oB`@iAXu@TkAh@a@NYFa@Da@?cAAO?k@AkAIk@Qc@Wg@o@IYSm@m@yBi@sASa@s@oAeAeAy@_@e@Oy@E{@@w@DyAJuBRcCReAJaAJY@WBUBYBe@?i@?[Ac@?yAAE?mAAw@?gBAq@CYAYAG?CAIAECECECKMCEACGIEMEO_@{AUeAIUGMEIKOOQOOq@q@QOOMMKQMk@o@KKIKMQIKGKKMIGCEGCGAA?EAG@EJA@CHALAJ?R?T?VA^?h@A`E?zB@XBnCInB]jFGrAGdAC|AU?eAFg@C}@Ks@CGAY?YD]Fm@X_@Pw@d@eAn@k@VM@]He@Fo@Bi@?[E{Di@_@GwBMqC@oCRsCZeDj@a@J_AN{@Pp@vGaBhAgB`@cAXSN");

        var polyline5 = computePolyline(hindenburgStrUnderConstruction(), autobahn, aufDemGraben);
        assertThatPolylinesAreEqual(polyline5, "{pogHathu@G`@Gf@Gd@Kf@SjASfAWjAKf@I\\Sp@Ol@Ob@CJKZENQh@Ul@Qd@Sj@Yr@[t@u@dB]v@_@v@i@dA_@r@U`@Ub@U`@]p@Yn@Wl@O`@M\\Of@M\\Mf@Oj@[jA{@~CQl@Oj@c@vAWt@Yv@O`@O^ITCFk@pAWj@[l@S^U`@U`@W^W`@Y`@Y^WZWZc@j@i@n@g@r@q@x@s@`AW^Y^g@t@[f@S\\SX[h@Yf@Yh@c@v@a@v@a@x@U`@MVWf@S`@e@|@Yl@Wd@OXw@|As@tAk@bAU`@wBfEQ\\[f@GJ[b@}A|B]d@U\\U\\IPUh@KXAHCn@Bz@Dt@Dh@@TGBC@KBSHGx@");
    }

    @Test
    public void shouldRespectNoThroughTraffic() {
        var mozartStr = new GenericLocation(48.59521, 8.88391);
        var fritzLeharStr = new GenericLocation(48.59460, 8.88291);

        var polyline1 = computePolyline(ordinaryHerrenbergGraph(), mozartStr, fritzLeharStr);
        assertThatPolylinesAreEqual(polyline1, "_grgHkcfu@OjBC\\ARGjAKzAfBz@j@n@Rk@E}D");

        var polyline2 = computePolyline(ordinaryHerrenbergGraph(), fritzLeharStr, mozartStr);
        assertThatPolylinesAreEqual(polyline2, "gcrgHc}eu@D|DSj@k@o@gB{@J{AFkA@SB]NkB");
    }

    @Test
    public void shouldGoToNorthernMarienstr() {
        var eichendorfStr = new GenericLocation(48.59081, 8.87372);
        var marienStr = new GenericLocation(48.59447, 8.87208);

        var polyline1 = computePolyline(hindenburgStrUnderConstruction(), eichendorfStr, marienStr);
        assertThatPolylinesAreEqual(polyline1, "okqgHwcdu@WyAAGCIKFIFKDQHUJe@Rk@XmAh@sBfAi@~@c@j@{@r@c@Re@JC@o@LyAT");

        var polyline2 = computePolyline(hindenburgStrUnderConstruction(), marienStr, eichendorfStr);
        assertThatPolylinesAreEqual(polyline2, "mbrgHqycu@xAUn@MBAd@Kb@Sz@s@b@k@h@_ArBgAlAi@j@Yd@STKPIJEHGJGBH@FVxA");
    }

    @Test
    @Ignore
    public void shouldRespectTurnRestrictionAtPaulGerhardtJohannesStr() {
        var hauffstr = new GenericLocation(48.59204, 8.86903);
        var brentanoStr = new GenericLocation(48.59177, 8.87214);

        var polyline1 = computePolyline(hindenburgStrUnderConstruction(), hauffstr, brentanoStr);
        assertThatPolylinesAreEqual(polyline1, "okqgHwcdu@WyAAGCIKFIFKDQHUJe@Rk@XmAh@sBfAi@~@c@j@{@r@c@Re@JC@o@LyAT");

        var polyline2 = computePolyline(hindenburgStrUnderConstruction(), brentanoStr, hauffstr);
        assertThatPolylinesAreEqual(polyline2, "mbrgHqycu@xAUn@MBAd@Kb@Sz@s@b@k@h@_ArBgAlAi@j@Yd@STKPIJEHGJGBH@FVxA");
    }
}