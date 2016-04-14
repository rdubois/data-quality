// ============================================================================
//
// Copyright (C) 2006-2016 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.dataquality.datamasking.Functions;

import java.io.Serializable;

/**
 * created by jgonzalez on 19 juin 2015. See GenerateCreditCardSimple.
 *
 */
public class GenerateCreditCardLong extends GenerateCreditCardSimple<Long> implements Serializable {

    private static final long serialVersionUID = 7201691028765322530L;

    @Override
    protected Long doGenerateMaskedField(Long l) {
        super.generateCreditCard();
        return number;
    }
}
