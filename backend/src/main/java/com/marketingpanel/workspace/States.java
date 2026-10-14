package com.marketingpanel.workspace;

import java.util.LinkedHashMap;
import java.util.Map;

/** Indian states and UTs with GST state codes (first two digits of a GSTIN). */
public final class States {
    public static final Map<String, String> CODE_BY_STATE = new LinkedHashMap<>();

    static {
        String[][] d = {{"01", "Jammu and Kashmir"}, {"02", "Himachal Pradesh"}, {"03", "Punjab"}, {"04", "Chandigarh"},
                {"05", "Uttarakhand"}, {"06", "Haryana"}, {"07", "Delhi"}, {"08", "Rajasthan"}, {"09", "Uttar Pradesh"},
                {"10", "Bihar"}, {"11", "Sikkim"}, {"12", "Arunachal Pradesh"}, {"13", "Nagaland"}, {"14", "Manipur"},
                {"15", "Mizoram"}, {"16", "Tripura"}, {"17", "Meghalaya"}, {"18", "Assam"}, {"19", "West Bengal"},
                {"20", "Jharkhand"}, {"21", "Odisha"}, {"22", "Chhattisgarh"}, {"23", "Madhya Pradesh"}, {"24", "Gujarat"},
                {"26", "Dadra and Nagar Haveli and Daman and Diu"}, {"27", "Maharashtra"}, {"29", "Karnataka"}, {"30", "Goa"},
                {"31", "Lakshadweep"}, {"32", "Kerala"}, {"33", "Tamil Nadu"}, {"34", "Puducherry"},
                {"35", "Andaman and Nicobar Islands"}, {"36", "Telangana"}, {"37", "Andhra Pradesh"}, {"38", "Ladakh"}};
        for (String[] x : d) CODE_BY_STATE.put(x[1], x[0]);
    }

    private States() {}

    public static boolean valid(String state) {
        return state != null && CODE_BY_STATE.containsKey(state);
    }

    public static boolean gstinMatches(String gstin, String state) {
        return gstin != null && state != null && gstin.startsWith(CODE_BY_STATE.getOrDefault(state, "--"));
    }
}
