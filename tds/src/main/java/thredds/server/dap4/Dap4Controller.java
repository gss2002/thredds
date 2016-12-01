/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package thredds.server.dap4;

import dap4.core.data.DSPRegistry;
import dap4.core.util.DapContext;
import dap4.core.util.DapException;
import dap4.core.util.DapUtil;
import dap4.dap4lib.DapCodes;
import dap4.servlet.DSPFactory;
import dap4.servlet.DapCache;
import dap4.servlet.DapController;
import dap4.servlet.DapRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import thredds.core.TdsRequestedDataset;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

@Controller
@RequestMapping("/dap4")
public class Dap4Controller extends DapController
{

    //////////////////////////////////////////////////
    // Constants

    static final boolean DEBUG = false;

    static final boolean PARSEDEBUG = false;

    // NetcdfDataset enhancement to use: need only coord systems
    //static Set<NetcdfDataset.Enhance> ENHANCEMENT = EnumSet.of(NetcdfDataset.Enhance.CoordSystems);

    //////////////////////////////////////////////////
    // Type Decls

    static class Dap4Factory extends DSPFactory
    {

        public Dap4Factory()
        {
            // For TDS, we only need to register one DSP type: ThreddsDSP.
            // This is because we will always serve only NetcdfFile objects.
            // See D4TSServlet for a multiple registration case.
            DapCache.dspregistry.register(ThreddsDSP.class, DSPRegistry.LAST);
        }

    }

    static {
        DapCache.setFactory(new Dap4Factory());
    }

    //////////////////////////////////////////////////
    // Spring Elements

    @Autowired
    private ServletContext servletContext;

    @RequestMapping("**")
    public void handleRequest(HttpServletRequest req, HttpServletResponse res)
            throws IOException
    {
        super.handleRequest(req, res);
    }

    //////////////////////////////////////////////////
    // Constructor(s)

    public Dap4Controller()
    {
        super();
    }

    //////////////////////////////////////////////////////////

    @Override
    public void
    initialize()
    {
        assert this.servletContext != null;
        super.setservletcontext(this.servletContext);
        super.initialize();
    }

    @Override
    protected void
    doFavicon(String icopath, DapContext cxt)
            throws IOException
    {
        throw new UnsupportedOperationException("Favicon");
    }

    @Override
    protected void
    doCapabilities(DapContext cxt)
            throws IOException
    {
        DapRequest drq = this.daprequest;
        addCommonHeaders(drq);
        OutputStream out = drq.getOutputStream();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, DapUtil.UTF8));
        pw.println("Capabilities page not supported");
        pw.flush();
    }

    @Override
    public long
    getBinaryWriteLimit()
    {
        return DEFAULTBINARYWRITELIMIT;
    }

    @Override
    public String
    getServletID()
    {
        return "dap4";
    }

    @Override
    public String
    getResourcePath(DapRequest drq, String location)
            throws IOException
    {
        String prefix = (String) this.dapcxt.get("RESOURCEDIR");
        String realpath;
        if(prefix != null) {
            realpath = DapUtil.canonjoin(prefix, location);
        } else
            realpath = TdsRequestedDataset.getLocationFromRequestPath(location);

        if(!TESTING) {
            if(!TdsRequestedDataset.resourceControlOk(drq.getRequest(), drq.getResponse(), realpath))
                throw new DapException("Not authorized: " + location)
                        .setCode(DapCodes.SC_FORBIDDEN);
        }
        File f = new File(realpath);
        if(!f.exists() || !f.canRead())
            throw new DapException("Not found: " + location)
                    .setCode(DapCodes.SC_NOT_FOUND);
        //ncfile = TdsRequestedDataset.getNetcdfFile(this.request, this.response, path);
        return realpath;
    }

}


